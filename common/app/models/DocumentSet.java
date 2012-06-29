package models;

import java.util.*;

import play.db.ebean.*;
import play.data.validation.Constraints.*;

import javax.persistence.*;

@Entity
public class DocumentSet extends Model {
    private static final long serialVersionUID = 1L;

    @Id
    public Long id;

    @Required
    public String query;

    @OneToMany(cascade=CascadeType.ALL)
    public Set<Tag> tags = new HashSet<Tag>();

    @OneToMany(cascade=CascadeType.ALL)
    public Set<Document> documents = new HashSet<Document>();
    
    @ManyToMany(mappedBy="documents")
    public Set<Node> nodes = new HashSet<Node>();

    public static Finder<Long, DocumentSet> find = new Finder<Long, DocumentSet>(Long.class, DocumentSet.class);
    
    public void addDocument(Document document) {
    	documents.add(document);
    	document.documentSet = this;
    }
}
