package org.overviewproject.jobhandler.documentset

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.actor.Actor
import akka.pattern.pipe
import org.overviewproject.jobhandler.JobProtocol._
import org.overviewproject.util.Logger
import DeleteHandlerFSM._
import akka.actor.FSM
import org.overviewproject.database.{ DocumentSetDeleter => NewDocumentSetDeleter }
import org.overviewproject.database.DocumentSetCreationJobDeleter

/**
 * [[DeleteHandler]] deletes a document set and all associated data if deletion is requested after
 * clustering begins.
 * Since the clustering worker is currently completely independent, we can't directly control the
 * status of the clustering process. We want to assume that no more data is being added to the
 * document set while it is deleted, so we want to wait until the clustering job has stopped.
 * The initial, hacky way to accomplish this goal:
 *   # The server sets the document set creation job state to `CANCELLED`
 *   # The clustering worker will delete the document set creation job, when it detects cancellation
 *   # The DeleteHandler will wait until the job has been deleted before deleting the document set
 *   # The DeleteHandler will fail if the job does not get deleted after a given timeout interval
 * Once we unify all worker processes, we can use a more rigorous approach.
 *
 * We also assume that the server will have sent cancellation notices to any jobs trying to clone the
 * document set being deleted. Clone jobs will notice that they're being cancelled before they notice
 * that the source has been deleted.
 *
 * Data to be deleted:
 *   * documents in search index and search index alias
 *   * Nodes created by clustering
 *   * LogEntries
 *   * Tags
 *   * Uploaded documents
 *   * Documents
 *   * DocumentProcessingErrors
 *   * SearchResults
 */
object DeleteHandlerProtocol {
  case class DeleteDocumentSet(documentSetId: Long, waitForJobRemoval: Boolean)
  case class DeleteReclusteringJob(jobId: Long)
}

object DeleteHandlerFSM {
  sealed trait State
  case object Idle extends State
  case object WaitingForRunningJobRemoval extends State
  case object Running extends State

  sealed trait Data
  case object NoData extends Data
  case class DeleteTarget(documentSetId: Long) extends Data
  case class DeleteTreeTarget(jobId: Long) extends Data
  case class RetryAttempts(documentSetId: Long, n: Int) extends Data
}

trait DeleteHandler extends Actor with FSM[State, Data] with SearcherComponents {
  import DeleteHandlerProtocol._
  import context.dispatcher

  val documentSetDeleter: DocumentSetDeleter
  val newDocumentSetDeleter: NewDocumentSetDeleter
  val jobDeleter: DocumentSetCreationJobDeleter
  val jobStatusChecker: JobStatusChecker

  val RetryTimer = "retry"

  protected val JobWaitDelay = 100 milliseconds
  protected val MaxRetryAttempts = 600

  private object Message {
    case object RetryDelete
    case object DeleteComplete
    case object DeleteReclusteringJobComplete
    case class SearchIndexDeleteFailed(error: Throwable)
    case class DeleteFailed(documentSetId: Long)
  }

  startWith(Idle, NoData)

  // FIXME: The waitForJobRemoval parameter of DeleteDocumentSet is now redundant, since
  // we need to check for and cancel jobs whenever we delete document sets
  when(Idle) {
    case Event(DeleteDocumentSet(documentSetId, true), _) => {
      if (jobStatusChecker.isJobRunning(documentSetId)) {
        setTimer(RetryTimer, Message.RetryDelete, JobWaitDelay, true)
        goto(WaitingForRunningJobRemoval) using (RetryAttempts(documentSetId, 1))
      } else {
        goto(Running) using (DeleteTarget(documentSetId))
      }
    }
    case Event(DeleteDocumentSet(documentSetId, false), _) => {
      if (jobStatusChecker.cancelJob(documentSetId)) {
        self ! DeleteDocumentSet(documentSetId, true)
        stay
      } 
      else goto(Running) using (DeleteTarget(documentSetId))
    }
      
    case Event(DeleteReclusteringJob(jobId), _) =>
      goto(Running) using (DeleteTreeTarget(jobId))
  }

  when(WaitingForRunningJobRemoval) {
    case Event(Message.RetryDelete, RetryAttempts(documentSetId, n)) => {
      val jobIsRunning = jobStatusChecker.isJobRunning(documentSetId)
      
      if (jobIsRunning && n >= MaxRetryAttempts) goto(Running)
      else if (jobIsRunning) stay using (RetryAttempts(documentSetId, n + 1))
      else goto(Running) using (DeleteTarget(documentSetId))
    }
  }

  when(Running) {
    case Event(Message.DeleteComplete, DeleteTarget(documentSetId)) => {
      context.parent ! JobDone(documentSetId)
      stop
    }
    case Event(Message.DeleteReclusteringJobComplete, DeleteTreeTarget(jobId)) => {
      context.parent ! JobDone(jobId)
      stop
    }
    case Event(Message.SearchIndexDeleteFailed(t), DeleteTarget(documentSetId)) => {
      Logger.error(s"Deleting indexed documents failed for $documentSetId", t)
      context.parent ! JobDone(documentSetId)
      stop
    }
    case Event(Message.DeleteFailed(documentSetId), _) => {
      Logger.error(s"Delete timed out waiting for job to cancel $documentSetId")
      context.parent ! JobDone(documentSetId)
      stop
    }
  }

  onTransition {
    case _ -> Running =>
      cancelTimer(RetryTimer)
      nextStateData match {
        case DeleteTarget(documentSetId) => deleteDocumentSet(documentSetId)
        case DeleteTreeTarget(jobId) => deleteReclusteringJob(jobId)
        case RetryAttempts(documentSetId, n) => self ! Message.DeleteFailed(documentSetId)
        case _ =>
      }
  }

  private def deleteDocumentSet(documentSetId: Long): Unit = {
    val ds = jobDeleter.deleteByDocumentSet(documentSetId)
      .map(_ => newDocumentSetDeleter.delete(documentSetId))
    val si = searchIndex.removeDocumentSet(documentSetId)
    
    val result = for {
      dsResult <- ds
      siResult <- si  
    } yield Message.DeleteComplete
    
   result.recover {
      case t: Throwable => Message.DeleteFailed(documentSetId)
    }  pipeTo self
  }
  
  private def deleteReclusteringJob(jobId: Long): Unit = {
    documentSetDeleter.deleteOneCancelledJobInformation(jobId)
    
    self ! Message.DeleteReclusteringJobComplete
  }
}
