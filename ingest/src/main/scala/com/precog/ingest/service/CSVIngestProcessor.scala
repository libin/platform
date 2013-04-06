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
package com.precog.ingest
package service

import akka.dispatch.{ExecutionContext, Future, Promise}

import au.com.bytecode.opencsv.CSVReader

import blueeyes.core.data.ByteChunk
import blueeyes.json._

import com.precog.common.Path
import com.precog.common.jobs.JobId
import com.precog.common.security.{APIKey, Authorities}
import com.precog.ingest.util.CsvType

import com.weiglewilczek.slf4s.Logging

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.{Channels, ReadableByteChannel, WritableByteChannel}

import scala.annotation.tailrec

import scalaz._

final class CSVIngestProcessor(apiKey: APIKey, path: Path, authorities: Authorities, batchSize: Int, ingest: IngestStore)(implicit M: Monad[Future], val executor: ExecutionContext) extends IngestProcessor with Logging {
  import scalaz.syntax.applicative._
  import scalaz.Validation._

  def writeChunkStream(chan: WritableByteChannel, chunk: ByteChunk): Future[Long] = {
    chunk match {
      case Left(bb) => writeChannel(chan, bb :: StreamT.empty[Future, ByteBuffer], 0L)
      case Right(stream) => writeChannel(chan, stream, 0L)
    }
  }

  def writeToFile(byteStream: ByteChunk): Future[(File, Long)] = {
    val file = File.createTempFile("async-ingest-", null)
    val outChannel = new FileOutputStream(file).getChannel()
    for (written <- writeChunkStream(outChannel, byteStream)) yield (file, written)
  }

  final private def writeChannel(chan: WritableByteChannel, stream: StreamT[Future, ByteBuffer], written: Long): Future[Long] = {
    stream.uncons flatMap {
      case Some((buf, tail)) =>
        // This is safe since the stream is coming directly from BE
        val safeBuf = buf.duplicate.rewind.asInstanceOf[ByteBuffer]
        //logger.trace("Writing buffer %s, remain: %d: %s".format(safeBuf.hashCode, safeBuf.remaining, safeBuf))
        try {
          val written0 = chan.write(safeBuf)
          writeChannel(chan, tail, written + written0)
        } catch {
          case t => logger.error("Failure on ByteBuffer read of %s (%d remaining)".format(safeBuf, safeBuf.remaining)); throw t
        }

      case None =>
        M.point { chan.close(); written }
    }
  }

  def readerBuilder(parseDirectives: Set[ParseDirective]): ValidationNel[String, java.io.Reader => CSVReader] = {
    def charOrError(s: Option[String], default: Char): ValidationNel[String, Char] = {
      s map {
        case s if s.length == 1 => success(s.charAt(0))
        case _ => failure("Expected a single character but found a string.")
      } getOrElse {
        success(default)
      } toValidationNel
    }

    val delimiter = charOrError(parseDirectives collectFirst { case CSVDelimiter(str) => str }, ',')
    val quote     = charOrError(parseDirectives collectFirst { case CSVQuote(str) => str }, '"')
    val escape    = charOrError(parseDirectives collectFirst { case CSVEscape(str) => str }, '\\')

    (delimiter |@| quote |@| escape) { (delimiter, quote, escape) =>
      (reader: java.io.Reader) => new CSVReader(reader, delimiter, quote, escape)
    }
  }

  @tailrec final def readBatch(reader: CSVReader, batch: Vector[Array[String]]): Vector[Array[String]] = {
    val nextRow = reader.readNext()
    if (nextRow == null || batch.size >= batchSize) batch else readBatch(reader, batch :+ nextRow)
  }

  /**
    * Normalize headers by turning them into `JPath`s. Normally, a field will
    * be mapped to a `JPath` simply by wrapping it in a `JPathField`. However,
    * in the case of duplicate headers, we turn that field into an array. So,
    * the header a,a,a will create objects of the form `{a:[_, _, _]}`.
    */
  def normalizeHeaders(headers: Array[String]): Array[JPath] = {
    val positions = headers.zipWithIndex.foldLeft(Map.empty[String, List[Int]]) {
      case (hdrs, (h, i)) =>
        val pos = i :: hdrs.getOrElse(h, Nil)
        hdrs + (h -> pos)
    }

    positions.toList.flatMap {
      case (h, Nil) =>
        Nil
      case (h, pos :: Nil) =>
        (pos -> JPath(JPathField(h))) :: Nil
      case (h, ps) =>
        ps.reverse.zipWithIndex map { case (pos, i) =>
            (pos -> JPath(JPathField(h), JPathIndex(i)))
        }
    }.sortBy(_._1).map(_._2).toArray
  }

  def ingestSync(reader: CSVReader, jobId: JobId): Future[BatchIngestResult] = {
    def readBatches(paths: Array[JPath], reader: CSVReader, total: Int, ingested: Int, errors: Vector[(Int, String)]): Future[BatchIngestResult] = {
      // TODO: handle errors in readBatch
      M.point(readBatch(reader, Vector())) flatMap { batch =>
        if (batch.isEmpty) {
          // the batch will only be empty if there's nothing left to read
          // TODO: Write out job completion information to the queue.
          M.point(BatchSyncResult(total, ingested, errors))
        } else {
          val types = CsvType.inferTypes(batch.iterator)
          val jvals = batch map { row =>
            (paths zip types zip row).foldLeft(JUndefined: JValue) { case (obj, ((path, tpe), s)) =>
                JValue.unsafeInsert(obj, path, tpe(s))
            }
          }

          ingest.store(apiKey, path, authorities, jvals, Some(jobId)) flatMap { _ =>
            readBatches(paths, reader, total + batch.length, ingested + batch.length, errors)
          }
        }
      }
    }

    M.point(reader.readNext()) flatMap { header =>
      if (header == null) {
        M.point(NotIngested("No CSV data was found in the request content."))
      } else {
        readBatches(normalizeHeaders(header), reader, 0, 0, Vector())
      }
    }
  }

  def processBatch(data: ByteChunk, parseDirectives: Set[ParseDirective], jobId: JobId, sync: Boolean): Future[BatchIngestResult] = {
    readerBuilder(parseDirectives).map { f =>
      val ingestResult = for {
        (file, size) <- writeToFile(data)
        result <- ingestSync(f(new InputStreamReader(new FileInputStream(file), "UTF-8")), jobId)
      } yield {
        file.delete()
        result
      }

      if (sync) {
        // must not return until everything is persisted to kafka central
        ingestResult
      } else {
        Promise.successful(AsyncSuccess)
      }
    }.valueOr { errors =>
      M.point(NotIngested(errors.list.mkString("; ")))
    }
  }

  def processStream(data: ByteChunk, parseDirectives: Set[ParseDirective]): Future[StreamingIngestResult] =
    Promise.successful(NotIngested("Streaming ingest of CSV data is not supported. Use the mode=batch query parameter to ingest in batch mode"))
}