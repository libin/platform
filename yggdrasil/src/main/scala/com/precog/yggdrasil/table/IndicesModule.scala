/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package table

import scala.annotation.tailrec

import blueeyes.json._

import com.precog.common._
import com.precog.bytecode._
import com.precog.yggdrasil.jdbm3._
import com.precog.yggdrasil.util._
import com.precog.util._

import TransSpecModule._

import scalaz._
import scalaz.syntax.monad._

import scala.collection.{Set => GenSet}
import scala.collection.mutable
import org.apache.commons.collections.primitives.ArrayIntList
import com.weiglewilczek.slf4s.Logging

// TODO: does not deal well with tables too big to fit in memory

object IndicesHelper {
  def assertSorted(buf: ArrayIntList): Unit = {
    val len = buf.size
    if (len == 0) return ()
    var last = buf.get(0)
    var i = 1
    while (i < len) {
      val z = buf.get(i)
      if (last > z) sys.error("buffer is out-of-order: %s" format buf)
      if (last == z) sys.error("buffer has duplicates: %s" format buf)
      last = z
      i += 1
    }
  }
}

import IndicesHelper._

trait IndicesModule[M[+_]]
    extends Logging
    with TransSpecModule
    with ColumnarTableTypes[M]
    with SliceTransforms[M] {
    self: ColumnarTableModule[M] =>

  // we will warn for tables with >1M rows.
  final def InMemoryLimit = 1000000L

  import TableModule._
  import trans._
  import trans.constants._

  import Table._
  import SliceTransform._
  import TransSpec.deepMap

  class TableIndex(private[table] val indices: List[SliceIndex]) {

    /**
     * Return the set of values we've seen for this group key.
     */
    def getUniqueKeys(keyId: Int): GenSet[RValue] = {
      // Union the sets we get from our slice indices.
      val set = mutable.Set.empty[RValue]
      indices.foreach(set ++= _.getUniqueKeys(keyId))
      set
    }

    /**
     * Return the set of values we've seen for this group key.
     */
    def getUniqueKeys(): GenSet[Seq[RValue]] = {
      // Union the sets we get from our slice indices.
      val set = mutable.Set.empty[Seq[RValue]]
      indices.foreach(set ++= _.getUniqueKeys())
      set
    }

    /**
     * Return the subtable where each group key in keyIds is set to
     * the corresponding value in keyValues.
     */
    def getSubTable(keyIds: Seq[Int], keyValues: Seq[RValue]): Table = {
      // Each slice index will build us a slice, so we just return a
      // table of those slices.
      //
      // Currently we assemble the slices eagerly. After some testing
      // it might be the case that we want to use StreamT in a more
      // traditional (lazy) manner.
      var size = 0L
      val slices: List[Slice] = indices.map { sliceIndex =>
        val rows = sliceIndex.getRowsForKeys(keyIds, keyValues)
        val slice = sliceIndex.buildSubSlice(rows)
        size += slice.size
        slice
      }

      // if (size > InMemoryLimit) {
      //   logger.warn("indexing large table (%s rows > %s)" format (size, InMemoryLimit))
      // }

      Table(StreamT.fromStream(M.point(slices.toStream)), ExactSize(size))
    }
  }

  object TableIndex {

    /**
     * Create an empty TableIndex.
     */
    def empty = new TableIndex(Nil)

    /**
     * Creates a TableIndex instance given an underlying table, a
     * sequence of "group key" trans-specs, and "value" trans-spec
     * which corresponds to the rows the index will provide.
     *
     * Despite being in M, the TableIndex will be eagerly constructed
     * as soon as the underlying slices are available.
     */
    def createFromTable(table: Table, groupKeys: Seq[TransSpec1], valueSpec: TransSpec1): M[TableIndex] = {

      def accumulate(buf: mutable.ListBuffer[SliceIndex], stream: StreamT[M, SliceIndex]): M[TableIndex] =
        stream.uncons flatMap {
          case None => M.point(new TableIndex(buf.toList))
          case Some((si, tail)) => { buf += si; accumulate(buf, tail) }
        }

      // We are given TransSpec1s; to apply these to slices we need to
      // create SliceTransforms from them.
      val sts = groupKeys.map(composeSliceTransform).toArray
      val vt = composeSliceTransform(valueSpec)

      val indices = table.slices flatMap { slice =>
        val streamTM = SliceIndex.createFromSlice(slice, sts, vt) map { si =>
          si :: StreamT.empty[M, SliceIndex]
        }
        
        StreamT wrapEffect streamTM
      }

      accumulate(mutable.ListBuffer.empty[SliceIndex], indices)
    }

    /**
     * For a list of slice indices (along with projection
     * information), return a table containing all the rows for which
     * any of the given indices match.
     *
     * NOTE: Only the first index's value spec is used to construct
     * the table, since it's assumed that all indices have the same
     * value spec.
     */
    def joinSubTables(tpls: List[(TableIndex, Seq[Int], Seq[RValue])]): Table = {

      // Filter out negative integers. This allows the caller to do
      // arbitrary remapping of their own Seq[RValue] by filtering
      // values they don't want.
      val params: List[(Seq[Int], Seq[RValue])] = tpls.map {
        case (index, ns, jvs) =>
          val (ns2, jvs2) = ns.zip(jvs).filter(_._1 >= 0).unzip
          (ns2, jvs2)
      }

      val sll: List[List[SliceIndex]] = tpls.map(_._1.indices)
      val orderedIndices: List[List[SliceIndex]] = sll.transpose

      var size = 0L
      val slices: List[Slice] = orderedIndices.map { indices =>
        val slice = SliceIndex.joinSubSlices(indices.zip(params))
        size += slice.size
        slice
      }

//      if (size > InMemoryLimit) {
//        logger.warn("indexing large table (%s rows > %s)" format (size, InMemoryLimit))
//      }

      Table(StreamT.fromStream(M.point(slices.toStream)), ExactSize(size))
    }
  }
        
  /**
   * Provide fast access to a subslice based on one or more group key
   * values.
   * 
   * The SliceIndex currently uses in-memory data structures, although
   * this will have to change eventually. A "group key value" is
   * defined as an (Int, RValue). The Int part corresponds to the key
   * in the sequence of transforms used to build the index, and the
   * RValue part corresponds to the value we want the key to have.
   * 
   * SliceIndex is able to create subslices without rescanning the
   * underlying slice due to the fact that it already knows which rows
   * are valid for particular key combinations. For best results
   * valueSlice should already be materialized.
   */
  class SliceIndex(
    private[table] val vals: mutable.Map[Int, mutable.Set[RValue]],
    private[table] val dict: mutable.Map[(Int, RValue), ArrayIntList],
    private[table] val keyset: mutable.Set[Seq[RValue]],
    private[table] val valueSlice: Slice
  ) { self =>

    // TODO: We're currently maintaining a *lot* of indices. Once we
    // find the patterns of use, it'd be nice to reduce the amount of
    // data we're indexing if possible.

    /**
     * Return the set of values we've seen for this group key.
     */
    def getUniqueKeys(keyId: Int): GenSet[RValue] = vals(keyId)

    /**
     * Return the set of value combinations we've seen.
     */
    def getUniqueKeys(): GenSet[Seq[RValue]] = keyset

    /**
     * Return the subtable where each group key in keyIds is set to
     * the corresponding value in keyValues.
     */
    def getSubTable(keyIds: Seq[Int], keyValues: Seq[RValue]): Table =
      buildSubTable(getRowsForKeys(keyIds, keyValues))

    private def intersectBuffers(as: ArrayIntList, bs: ArrayIntList): ArrayIntList = {
      //assertSorted(as)
      //assertSorted(bs)
      var i = 0
      var j = 0
      val alen = as.size
      val blen = bs.size
      val out = new ArrayIntList(alen min blen)
      while (i < alen && j < blen) {
        val a = as.get(i)
        val b = bs.get(j)
        if (a < b) {
          i += 1
        } else if (a > b) {
          j += 1
        } else {
          out.add(a)
          i += 1
          j += 1
        }
      }
      out
    }

    private val emptyBuffer = new ArrayIntList(0)

    /**
     * Returns the rows specified by the given group key values.
     */
    private[table] def getRowsForKeys(keyIds: Seq[Int], keyValues: Seq[RValue]): ArrayIntList = {
      var rows: ArrayIntList = dict.getOrElse((keyIds(0), keyValues(0)), emptyBuffer)
      var i: Int = 1
      while (i < keyIds.length && !rows.isEmpty) {
        rows = intersectBuffers(rows, dict.getOrElse((keyIds(i), keyValues(i)), emptyBuffer))
        i += 1
      }
      rows
    }

    /**
     * Given a set of rows, builds the appropriate subslice.
     */
    private[table] def buildSubTable(rows: ArrayIntList): Table = {
      val slices = buildSubSlice(rows) :: StreamT.empty[M, Slice]
      Table(slices, ExactSize(rows.size))
    }

    // we can use this to avoid allocating/remapping empty slices
    private val emptySlice = new Slice {
      val size = 0
      val columns = Map.empty[ColumnRef, Column]
    }

    /**
     * Given a set of rows, builds the appropriate slice.
     */
    private[table] def buildSubSlice(rows: ArrayIntList): Slice =
      if (rows.isEmpty)
        emptySlice
      else
        valueSlice.remap(rows)
  }

  object SliceIndex {

    /**
     * Constructs an empty SliceIndex instance.
     */
    def empty = new SliceIndex(
      mutable.Map.empty[Int, mutable.Set[RValue]],
      mutable.Map.empty[(Int, RValue), ArrayIntList],
      mutable.Set.empty[Seq[RValue]],
      new Slice {
        def size = 0
        def columns = Map.empty[ColumnRef, Column]
      }
    )

    /**
     * Creates a SliceIndex instance given an underlying table, a
     * sequence of "group key" trans-specs, and "value" trans-spec
     * which corresponds to the rows the index will provide.
     *
     * Despite being in M, the SliceIndex will be eagerly constructed
     * as soon as the underlying Slice is available.
     */
    def createFromTable(table: Table, groupKeys: Seq[TransSpec1], valueSpec: TransSpec1): M[SliceIndex] = {

      val sts = groupKeys.map(composeSliceTransform).toArray
      val vt = composeSliceTransform(valueSpec)

      table.slices.uncons flatMap {
        case Some((slice, _)) => createFromSlice(slice, sts, vt)
        case None => M.point(SliceIndex.empty)
      }
    }

    /**
     * Given a slice, group key transforms, and a value transform,
     * builds a SliceIndex.
     * 
     * This is the heart of the indexing algorithm. We'll assemble a
     * 2D array of RValue (by row/group key) and then do all the work
     * necessary to associate them into the maps and sets we
     * ultimately need to construct the SliceIndex.
     */
    private[table] def createFromSlice(slice: Slice, sts: Array[SliceTransform1[_]], vt: SliceTransform1[_]): M[SliceIndex] = {
      val numKeys = sts.length
      val n = slice.size
      val vals = mutable.Map.empty[Int, mutable.Set[RValue]]
      val dict = mutable.Map.empty[(Int, RValue), ArrayIntList]
      val keyset = mutable.Set.empty[Seq[RValue]]

      readKeys(slice, sts) flatMap { keys =>
        // build empty initial jvalue sets for our group keys
        Loop.range(0, numKeys)(vals(_) = mutable.Set.empty[RValue])
  
        var i = 0
        while (i < n) {
          var dead = false
          val row = new Array[RValue](numKeys)
          var k = 0
          while (!dead && k < numKeys) {
            val jv = keys(k)(i)
            if (jv != null) {
              row(k) = jv
            } else {
              dead = true
            }
            k += 1
          }
  
          if (!dead) {
            keyset.add(row)
            k = 0
            while (k < numKeys) {
              val jv = row(k)
              vals.get(k).map { jvs =>
                jvs.add(jv)
                val key = (k, jv)
                if (dict.contains(key)) {
                  dict(key).add(i)
                } else {
                  val as = new ArrayIntList(0)
                  as.add(i)
                  dict(key) = as
                }
              }
              k += 1
            }
          }
          i += 1
        }
  
        vt(slice) map {
          case (_, slice2) =>
            new SliceIndex(vals, dict, keyset, slice2.materialized)
        }
      }
    }

    /**
     * Given a slice and an array of group key transforms, we want to
     * build a two-dimensional array which contains the values
     * per-row, per-column. This is how we deal with the fact that our
     * data store is column-oriented but the associations we want to
     * perform are row-oriented.
     */
    private[table] def readKeys(slice: Slice, sts: Array[SliceTransform1[_]]): M[Array[Array[RValue]]] = {
      val n = slice.size
      val numKeys = sts.length
      
      val keys: mutable.ArrayBuffer[M[Array[RValue]]] =
        new mutable.ArrayBuffer[M[Array[RValue]]](numKeys)
      
      (0 until numKeys) foreach { _ => keys += null.asInstanceOf[M[Array[RValue]]] }

      var k = 0
      while (k < numKeys) {
        val st: SliceTransform1[_] = sts(k)
        
        keys(k) = st(slice) map {
          case (_, keySlice) => {
            val arr = new Array[RValue](n)
            
            var i = 0
            while (i < n) {
              val rv = keySlice.toRValue(i)
              rv match {
                case CUndefined =>
                case rv => arr(i) = rv
              }
              i += 1
            }
            
            arr
          }
        }
        
        k += 1
      }
      
      val back = (0 until keys.length).foldLeft(M.point(Vector.fill[Array[RValue]](numKeys)(null))) {
        case (accM, i) => {
          val arrM = keys(i)
          
          M.apply2(accM, arrM) { (acc, arr) =>
            acc.updated(i, arr)
          }
        }
      }

      back map { _.toArray }
    }

    private def unionBuffers(as: ArrayIntList, bs: ArrayIntList): ArrayIntList = {
      //assertSorted(as)
      //assertSorted(bs)
      var i = 0
      var j = 0
      val alen = as.size
      val blen = bs.size
      val out = new ArrayIntList(alen max blen)
      while (i < alen && j < blen) {
        val a = as.get(i)
        val b = bs.get(j)
        if (a < b) {
          out.add(a)
          i += 1
        } else if (a > b) {
          out.add(b)
          j += 1
        } else {
          out.add(a)
          i += 1
          j += 1
        }
      }
      while (i < alen) {
        out.add(as.get(i))
        i += 1
      }
      while (j < blen) {
        out.add(bs.get(j))
        j += 1
      }
      out
    }

    /**
     * For a list of slice indices, return a slice containing all the
     * rows for which any of the indices matches.
     *
     * NOTE: Only the first slice's value spec is used to construct
     * the slice since it's assumed that all slices have the same
     * value spec.
     */
    def joinSubSlices(tpls: List[(SliceIndex, (Seq[Int], Seq[RValue]))]): Slice =
      tpls match {
        case Nil =>
          sys.error("empty slice") // FIXME
        case (index, (ids, vals)) :: tail =>
          
          var rows = index.getRowsForKeys(ids, vals)
          tail.foreach { case (index, (ids, vals)) =>
            rows = unionBuffers(rows, index.getRowsForKeys(ids, vals))
          }
          index.buildSubSlice(rows)
      }
  }
}
