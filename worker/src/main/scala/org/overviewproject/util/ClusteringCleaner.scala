package org.overviewproject.util

import scala.concurrent.Future
import org.overviewproject.models.DocumentSetCreationJob
import org.overviewproject.database.SlickClient
import scala.slick.jdbc.StaticQuery.interpolation
import org.overviewproject.database.Slick.simple._
import org.overviewproject.models.DocumentSetCreationJobState
import org.overviewproject.models.tables.{ DocumentSetCreationJobs, DocumentSetCreationJobNodes, NodeDocuments, Nodes, Trees }

trait ClusteringCleaner extends SlickClient {

  def updateValidJob(job: DocumentSetCreationJob): Future[Unit] = db { implicit session =>
    val updatedJob = sqlu"""
          WITH ids AS (
            SELECT id FROM document_set_creation_job
            WHERE state <> ${DocumentSetCreationJobState.Cancelled.id}
          )
          UPDATE document_set_creation_job
          SET state = ${job.state.id},
              retry_attempts = ${job.retryAttempts},
              status_description = ${job.statusDescription}
          WHERE id IN (SELECT id FROM ids)    
        """
    updatedJob.execute
  }

  def treeExists(jobId: Long): Future[Boolean] = db { implicit session =>
    Trees.filter(_.jobId === jobId).firstOption.isDefined
  }

  def deleteNodes(jobId: Long): Future[Unit] = db { implicit session =>

    val allTheDeletes = sqlu"""
        WITH root_node AS (
          DELETE FROM document_set_creation_job_node WHERE document_set_creation_job_id = $jobId
          RETURNING node_id
        ), nodes AS (
          SELECT id FROM node WHERE root_id IN (SELECT node_id FROM root_node)
        ), nd_delete AS (
          DELETE FROM node_document WHERE node_id IN (SELECT id FROM nodes)
          RETURNING node_id
        )
        DELETE FROM node WHERE id IN (SELECT id FROM nodes) 
      """

    allTheDeletes.execute
  }

  def deleteDocumentSetCreationJobNode(jobId: Long): Future[Unit] = db { implicit session =>
    val documentSetCreationJobNodes = DocumentSetCreationJobNodes.filter(_.documentSetCreationJobId === jobId)

    documentSetCreationJobNodes.delete
  }

  def deleteJob(jobId: Long): Future[Unit] = db { implicit session =>
    val documentSetCreationJobNodes = DocumentSetCreationJobNodes.filter(_.documentSetCreationJobId === jobId)
    val jobs = DocumentSetCreationJobs.filter(_.id === jobId)

    documentSetCreationJobNodes.delete
    jobs.delete
  }
}