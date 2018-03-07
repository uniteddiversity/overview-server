package com.overviewdocs.ingest

import akka.stream.{Graph,Materializer,Outlet,OverflowStrategy,UniformFanOutShape}
import akka.stream.scaladsl.{Flow,GraphDSL,Partition,Sink}
import akka.util.ByteString
import java.util.concurrent.Callable
import scala.concurrent.{ExecutionContext,Future}

import com.overviewdocs.blobstorage.BlobStorage
import com.overviewdocs.ingest.models.WrittenFile2
import com.overviewdocs.ingest.pipeline.Step
import com.overviewdocs.models.BlobStorageRef
import com.overviewdocs.util.AwaitMethod
import org.overviewproject.mime_types.MimeTypeDetector

/** Augments each WrittenFile2, setting contentType if it's
  * `application/octet-stream` and adding `pipelineOptions.remainingSteps` if
  * it's empty; outputs the augmented item to _one_ of its outlets.
  *
  * Each input can be:
  *
  * * a WrittenFile2 from the user which we have never examined.
  * * a WrittenFile2 that was output by a Step with the intent of recursing,
  *   which we have never examined.
  * * a WrittenFile2s that was output by a Step that already knows what the
  *   next Step should be: `pipelineOptions.stepsRemaining.nonEmpty`.
  *
  * This Graph has `steps.size` outlets: one per (hard-coded) Step. Outputs
  * on outlet 0 should connect to `Step.all(0)`'s inlet. Outputs on outlet 1
  * should connect to `Step.all(1)`'s inlet. And so on.
  *
  * We don't write our results to the database: we assume contentType
  * detection is relatively quick. Most of the time, it relies on filename
  * extension. Only rarely does it use magic numbers from BlobStorage. Plus,
  * if there's a large backlog of magic-number detection to handle, that means
  * downstream isn't processing files quickly enough.
  */
class Decider(
  steps: Vector[Step],
  blobStorage: BlobStorage,

  /** Number of magic-number detections to run in parallel.
    *
    * Usually, the slow part is reading from BlobStorage.
    */
  parallelism: Int = 2,

  /** Number of output items to keep in memory.
    *
    * As this is a fan-out, items only pass through it as quickly as they pass
    * through its slowest consumer (Step). Set this high enough to keep Steps
    * saturated with work.
    *
    * A WrittenFile2 consumes ~200 bytes plus its metadata. Assume 5kb per file.
    */
  outputBufferSize: Int = 1000
) extends AwaitMethod {
  def graph(implicit ec: ExecutionContext, mat: Materializer): Graph[UniformFanOutShape[WrittenFile2, WrittenFile2], akka.NotUsed] = {
    val flow = Flow.apply[WrittenFile2]
      .mapAsyncUnordered(parallelism)(ensurePipelineStepsRemaining _)
      .buffer(outputBufferSize, OverflowStrategy.backpressure)

    GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val addPipelineStepsRemaining = builder.add(flow)

      val partition = builder.add(Partition[WrittenFile2](steps.size, getOutletIndex _))

      addPipelineStepsRemaining ~> partition

      // The compiler seems to need help typecasting outlets
      val outlets: Seq[Outlet[WrittenFile2]] = partition.outlets.map(_.asInstanceOf[Outlet[WrittenFile2]])
      UniformFanOutShape(addPipelineStepsRemaining.in, outlets: _*)
    }
  }

  private def buildGetBytes(
    blobLocation: String
  )(implicit mat: Materializer): Callable[Array[Byte]] = { () =>
    val maxNBytes = Decider.mimeTypeDetector.getMaxGetBytesLength
    var nBytesSeen = 0 // icky way of tracking state
    val byteStringFuture: Future[ByteString] = blobStorage.get(blobLocation)
      .takeWhile(bs => {
        nBytesSeen += bs.size
        nBytesSeen < maxNBytes
      })
      .runWith(Sink.fold(ByteString.empty)(_ ++ _))

    val byteString = await(byteStringFuture)
    byteString.slice(0, maxNBytes).toArray
  }

  private def detectMimeType(
    filename: String,
    blob: BlobStorageRef
  )(implicit ec: ExecutionContext, mat: Materializer): Future[String] = {
    Future {
      Decider.mimeTypeDetector.detectMimeType(filename, buildGetBytes(blob.location))
    }
  }

  protected[ingest] def getContentTypeNoParameters(
    input: WrittenFile2
  )(implicit ec: ExecutionContext, mat: Materializer): Future[String] = {
    val MediaTypeRegex = "^([^;]+).*$".r

    input.contentType match {
      case "application/octet-stream" => detectMimeType(input.filename, input.blob.ref)
      case MediaTypeRegex(withoutParameter) => Future.successful(withoutParameter)
      case _ => detectMimeType(input.filename, input.blob.ref)
    }
  }

  protected[ingest] def ensurePipelineStepsRemaining(
    input: WrittenFile2
  )(implicit ec: ExecutionContext, mat: Materializer): Future[WrittenFile2] = {
    if (input.pipelineOptions.stepsRemaining.nonEmpty) {
      Future.successful(input)
    } else {
      for {
        detectedContentType <- getContentTypeNoParameters(input)
      } yield {
        val steps = Decider.handlers.get(detectedContentType)
          .orElse(Decider.handlers.get(detectedContentType.replaceFirst("/.*", "/*")))
          .getOrElse(Decider.pipelines.Unhandled)
          // Filter out "OCR"
          .filter(_ match {
            case Step.Ocr if !input.pipelineOptions.ocr => false
            case _ => true
          })

        input.copy(pipelineOptions=input.pipelineOptions.copy(
          stepsRemaining=steps.map(_.toString)
        ))
      }
    }
  }

  private def getOutletIndex(inputWithPipeline: WrittenFile2): Int = {
    val id = inputWithPipeline.pipelineOptions.stepsRemaining.head
    steps.indexWhere(_.id == id)
  }
}

object Decider {
  protected[ingest] val mimeTypeDetector = new MimeTypeDetector

  private object pipelines {
    val Pdf = Vector(Step.Ocr, Step.SplitExtract)
    val Office = Vector(Step.Office, Step.SplitExtract)
    val Unhandled = Vector(Step.Unhandled)
  }

  private val handlers = Map(
    "application/pdf" -> pipelines.Pdf,

    "application/clarisworks" -> pipelines.Office,
    "application/excel" -> pipelines.Office,
    "application/macwriteii" -> pipelines.Office,
    "application/msexcel" -> pipelines.Office,
    "application/mspowerpoint" -> pipelines.Office,
    "application/msword" -> pipelines.Office,
    "application/prs.plucker" -> pipelines.Office,
    "application/rtf" -> pipelines.Office,
    "application/tab-separated-values" -> pipelines.Office,
    "application/vnd.corel-draw" -> pipelines.Office,
    "application/vnd.lotus-1-2-3" -> pipelines.Office,
    "application/vnd.lotus-wordpro" -> pipelines.Office,
    "application/vnd.ms-excel" -> pipelines.Office,
    "application/vnd.ms-excel.sheet.binary.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-excel.sheet.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-excel.template.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-powerpoint" -> pipelines.Office,
    "application/vnd.ms-powerpoint.presentation.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-powerpoint.slideshow.macroEnabled.12" -> pipelines.Office,
    "application/vnd.ms-powerpoint.template.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-publisher" -> pipelines.Office,
    "application/vnd.ms-word" -> pipelines.Office,
    "application/vnd.ms-word.document.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-word.template.macroenabled.12" -> pipelines.Office,
    "application/vnd.ms-works" -> pipelines.Office,
    "application/vnd.oasis.opendocument.chart" -> pipelines.Office,
    "application/vnd.oasis.opendocument.chart-template" -> pipelines.Office,
    "application/vnd.oasis.opendocument.graphics" -> pipelines.Office,
    "application/vnd.oasis.opendocument.graphics-flat-xml" -> pipelines.Office,
    "application/vnd.oasis.opendocument.graphics-template" -> pipelines.Office,
    "application/vnd.oasis.opendocument.presentation" -> pipelines.Office,
    "application/vnd.oasis.opendocument.presentation-flat-xml" -> pipelines.Office,
    "application/vnd.oasis.opendocument.presentation-template" -> pipelines.Office,
    "application/vnd.oasis.opendocument.spreadsheet" -> pipelines.Office,
    "application/vnd.oasis.opendocument.spreadsheet-flat-xml" -> pipelines.Office,
    "application/vnd.oasis.opendocument.spreadsheet-template" -> pipelines.Office,
    "application/vnd.oasis.opendocument.text" -> pipelines.Office,
    "application/vnd.oasis.opendocument.text-flat-xml" -> pipelines.Office,
    "application/vnd.oasis.opendocument.text-master" -> pipelines.Office,
    "application/vnd.oasis.opendocument.text-template" -> pipelines.Office,
    "application/vnd.oasis.opendocument.text-web" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.presentationml.slide" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.presentationml.template" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.spreadsheetml.template" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> pipelines.Office,
    "application/vnd.openxmlformats-officedocument.wordprocessingml.template" -> pipelines.Office,
    "application/vnd.palm" -> pipelines.Office,
    "application/vnd.stardivision.writer-global" -> pipelines.Office,
    "application/vnd.sun.xml.calc" -> pipelines.Office,
    "application/vnd.sun.xml.calc.template" -> pipelines.Office,
    "application/vnd.sun.xml.draw" -> pipelines.Office,
    "application/vnd.sun.xml.draw.template" -> pipelines.Office,
    "application/vnd.sun.xml.impress" -> pipelines.Office,
    "application/vnd.sun.xml.impress.template" -> pipelines.Office,
    "application/vnd.sun.xml.writer" -> pipelines.Office,
    "application/vnd.sun.xml.writer.global" -> pipelines.Office,
    "application/vnd.sun.xml.writer.template" -> pipelines.Office,
    "application/vnd.visio" -> pipelines.Office,
    "application/vnd.wordperfect" -> pipelines.Office,
    "application/wordperfect" -> pipelines.Office,
    "application/x-123" -> pipelines.Office,
    "application/x-aportisdoc" -> pipelines.Office,
    "application/x-dbase" -> pipelines.Office,
    "application/x-dbf" -> pipelines.Office,
    "application/x-doc" -> pipelines.Office,
    "application/x-dos_ms_excel" -> pipelines.Office,
    "application/x-excel" -> pipelines.Office,
    "application/x-extension-txt" -> pipelines.Office,
    "application/x-fictionbook+xml" -> pipelines.Office,
    "application/x-hwp" -> pipelines.Office,
    "application/x-iwork-keynote-sffkey" -> pipelines.Office,
    "application/x-msexcel" -> pipelines.Office,
    "application/x-ms-excel" -> pipelines.Office,
    "application/x-quattropro" -> pipelines.Office,
    "application/x-t602" -> pipelines.Office,
    "application/x-wpg" -> pipelines.Office,
    "image/x-freehand" -> pipelines.Office,

    // Text types: we're using Office now, but we probably shouldn't
    // https://www.pivotaltracker.com/story/show/76453196
    // https://www.pivotaltracker.com/story/show/76453264
    "application/csv" -> pipelines.Office,
    "application/javascript" -> pipelines.Office,
    "application/json" -> pipelines.Office,
    "application/xml" -> pipelines.Office,
    "text/comma-separated-values" -> pipelines.Office,
    "text/html" -> pipelines.Office, // TODO anything else: LibreOffice is uniquely inept with HTML
    "text/*" -> pipelines.Office
  )
}