package models

import org.specs2.mock._
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class PersistentDocumentListSpec extends Specification with Mockito {
  implicit val unusedConnection: java.sql.Connection = null
  
  "PersistDocumentList" should {
    
    trait MockComponents extends Scope {
      val loader = mock[PersistentDocumentListDataLoader]
      val parser = mock[PersistentDocumentListParser]
      val nodeIds = Seq(1l, 2l, 4l)
      val documentIds = Seq(5l, 6l)
      val persistentDocumentList = 
        new PersistentDocumentList("1, 2, 4", "5, 6", loader, parser)

      
    }
    "extract ids from input strings" in  new MockComponents {
      val dummyDocumentData = Nil
      
      loader loadSelectedDocumentSlice(nodeIds, documentIds, 0, 10) returns
      	dummyDocumentData
      
      val documents = persistentDocumentList.loadSlice(0, 10)
      
      there was one(loader).loadSelectedDocumentSlice(nodeIds, documentIds, 0, 10)
    }
    
    "call loader and parser to create Documents" in new MockComponents {
      val dummyDocumentData = Nil
      val dummyDocuments = List(core.Document(1l, "title", "text", "view"))
      
      loader loadSelectedDocumentSlice(nodeIds, documentIds, 0, 10) returns
        dummyDocumentData
      parser createDocuments(dummyDocumentData) returns dummyDocuments
      
      val documents = persistentDocumentList.loadSlice(0, 10)
      
      there was one(loader).loadSelectedDocumentSlice(nodeIds, documentIds, 0, 10)
      there was one(parser).createDocuments(dummyDocumentData)
      
      documents must be equalTo(dummyDocuments)
    }
    
    "be constructable with default loader and parser" in {
      val persistentDocumentList = new PersistentDocumentList("", "")
      
      success
    }
  }

}