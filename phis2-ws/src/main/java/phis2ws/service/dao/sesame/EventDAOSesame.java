//******************************************************************************
//                          EventDAOSesame.java
// SILEX-PHIS
// Copyright © INRA 2018
// Creation date: 12  nov. 2018
// Contact: andreas.garcia@inra.fr, anne.tireau@inra.fr, pascal.neveu@inra.fr
//******************************************************************************
package phis2ws.service.dao.sesame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jena.arq.querybuilder.UpdateBuilder;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.update.UpdateRequest;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import phis2ws.service.PropertiesFileManager;
import phis2ws.service.configuration.DateFormat;
import phis2ws.service.dao.manager.DAOSesame;
import phis2ws.service.dao.phis.UserDaoPhisBrapi;
import phis2ws.service.documentation.StatusCodeMsg;
import phis2ws.service.model.User;
import phis2ws.service.ontologies.Contexts;
import phis2ws.service.ontologies.Oeev;
import phis2ws.service.ontologies.Rdf;
import phis2ws.service.ontologies.Rdfs;
import phis2ws.service.ontologies.Time;
import phis2ws.service.utils.POSTResultsReturn;
import phis2ws.service.utils.UriGenerator;
import phis2ws.service.utils.dates.Dates;
import phis2ws.service.utils.sparql.SPARQLQueryBuilder;
import phis2ws.service.view.brapi.Status;
import phis2ws.service.view.model.phis.Annotation;
import phis2ws.service.view.model.phis.ConcernedItem;
import phis2ws.service.view.model.phis.Event;
import phis2ws.service.view.model.phis.Property;

/**
 * DAO for Events
 * @update [Andreas Garcia] 14 Feb., 2019: Add event detail service
 * @update [Andreas Garcia] 5 March, 2019: Add event insertion
 * @author Andreas Garcia <andreas.garcia@inra.fr>
 */
public class EventDAOSesame extends DAOSesame<Event> {
    final static Logger LOGGER = LoggerFactory.getLogger(EventDAOSesame.class);
    
    // constants used for SPARQL names in the SELECT
    private static final String CONCERNED_ITEM_URI_SELECT_NAME = "concernedItemUri";
    private static final String CONCERNED_ITEM_URI_SELECT_NAME_SPARQL = "?" + CONCERNED_ITEM_URI_SELECT_NAME;
    private static final String CONCERNED_ITEM_TYPE_SELECT_NAME = "concernedItemType";
    private static final String CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL = "?" + CONCERNED_ITEM_TYPE_SELECT_NAME;
    private static final String CONCERNED_ITEM_LABEL_SELECT_NAME = "concernedItemLabel";
    private static final String CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL = "?" + CONCERNED_ITEM_LABEL_SELECT_NAME;
    private static final String CONCERNED_ITEM_LABELS_SELECT_NAME = "concernedItemLabels";
    private static final String CONCERNED_ITEM_LABELS_SELECT_NAME_SPARQL = "?" + CONCERNED_ITEM_LABELS_SELECT_NAME;
    
    private static final String TIME_SELECT_NAME = "time";
    private static final String TIME_SELECT_NAME_SPARQL = "?" + TIME_SELECT_NAME;
    
    private static final String DATETIMESTAMP_SELECT_NAME = "dateTimeStamp";
    private static final String DATETIMESTAMP_SELECT_NAME_SPARQL = "?" + DATETIMESTAMP_SELECT_NAME;

    public EventDAOSesame(User user) {
        super(user);
    }
    
    /**
     * Set a search query to select an URI and add a filter according to it 
     * if necessary
     * @example SparQL filter added:
     *  SELECT DISTINCT  ?uri
     *  WHERE {
     *    FILTER ( (regex (str(?uri), "http://www.phenome-fppn.fr/id/event/5a1b3c0d-58af-4cfb-811e-e141b11453b1", "i"))
     *  }
     *  GROUP BY ?uri
     * @param query
     * @param searchUri
     * @param inGroupBy
     * @return the value of the URI's value in the SELECT
     */
    private String prepareSearchQueryUri(SPARQLQueryBuilder query, String searchUri, boolean inGroupBy) {
        query.appendSelect(URI_SELECT_NAME_SPARQL);
        
        if(inGroupBy){
            query.appendGroupBy(URI_SELECT_NAME_SPARQL);
        }
        if (searchUri != null) {
            query.appendAndFilter("regex (str(" + URI_SELECT_NAME_SPARQL + ")" + ", \"" + searchUri + "\", \"i\")");
        }
        return URI_SELECT_NAME_SPARQL;
    }
    
    /**
     * Set a search query to select a type and to filter according to it 
     * if necessary
     * @example SparQL filter added:
     *  SELECT DISTINCT ?rdfType
     *  WHERE {
     *    ?rdfType  <http://www.w3.org/2000/01/rdf-schema#subClassOf>*  <http://www.opensilex.org/vocabulary/oeev#MoveFrom> . 
     *  }
     *  GROUP BY ?rdfType
     * @param query
     * @param uriSelectNameSparql
     * @param searchType
     * @param inGroupBy
     */
    private void prepareSearchQueryType(SPARQLQueryBuilder query, String uriSelectNameSparql, String searchType, boolean inGroupBy) {
        query.appendSelect(RDF_TYPE_SELECT_NAME_SPARQL);
        if(inGroupBy){
            query.appendGroupBy(RDF_TYPE_SELECT_NAME_SPARQL);
        }
        query.appendTriplet(uriSelectNameSparql, Rdf.RELATION_TYPE.toString(), RDF_TYPE_SELECT_NAME_SPARQL, null);
        if (searchType != null) {
            query.appendTriplet(RDF_TYPE_SELECT_NAME_SPARQL, "<" + Rdfs.RELATION_SUBCLASS_OF.toString() + ">*", searchType, null);
        } else {
            query.appendTriplet(RDF_TYPE_SELECT_NAME_SPARQL, "<" + Rdfs.RELATION_SUBCLASS_OF.toString() + ">*", Oeev.CONCEPT_EVENT.toString(), null);
        }    
    }
    
    /**
     * Set a search query to applies the concerned items label filter. 
     * This function DOES NOT make the query return the events concerned items 
     * informations. This is eventually done by another query further in the 
     * process.
     * @example SparQL filter added:
     *  WHERE {
     *    ?uri  <http://www.opensilex.org/vocabulary/oeev#concerns>  ?concernedItemUri  . 
     *    ?concernedItemUri  <http://www.w3.org/2000/01/rdf-schema#label>  ?concernedItemLabel  . 
     *  }
     * @param query
     * @param uriSelectNameSparql
     * @param searchConcernedItemLabel
     * @param searchConcernedItemUri
     * @param inGroupBy
     */
    private void prepareSearchQueryConcernedItemFilter(SPARQLQueryBuilder query, String uriSelectNameSparql, String searchConcernedItemLabel, String searchConcernedItemUri) {

        if (searchConcernedItemLabel != null || searchConcernedItemUri != null) {
            query.appendTriplet(uriSelectNameSparql, Oeev.RELATION_CONCERNS.toString(), CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, null);
            
            if (searchConcernedItemLabel != null) {
                query.appendTriplet(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, Rdfs.RELATION_LABEL.toString(), CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL, null);
                
                query.appendAndFilter("regex(" + CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL + ", \"" + searchConcernedItemLabel + "\", \"i\")");
            }
            
            if (searchConcernedItemUri != null) {
                query.appendAndFilter("regex (str(" + CONCERNED_ITEM_URI_SELECT_NAME_SPARQL + ")" + ", \"" + searchConcernedItemUri + "\", \"i\")");
            }
        }
    }

    /**
     * Set a search query to select a datetime from an instant and to filter 
     * according to it if necessary
     * @example SparQL filter added:
     *  SELECT DISTINCT ?dateTimeStamp
     *  WHERE {
     *    ?uri  <http://www.w3.org/2006/time#hasTime>  ?time  . 
     *    ?time  <http://www.w3.org/2006/time#inXSDDateTimeStamp>  ?dateTimeStamp  . 
     *    BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str(?dateTimeStamp)) as ?dateTime) .
     *    BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str("2017-09-10T12:00:00+01:00")) as ?dateRangeStartDateTime) .
     *    BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str("2017-09-12T12:00:00+01:00")) as ?dateRangeEndDateTime) .
     *  }
     *  GROUP BY ?dateTimeStamp
     * @param query
     * @param uriSelectNameSparql
     * @param searchDateTimeRangeStartString
     * @param searchDateTimeRangeEndString
     * @param inGroupBy
     */
    private void prepareSearchQueryDateTime(SPARQLQueryBuilder query, String uriSelectNameSparql, String searchDateTimeRangeStartString, String searchDateTimeRangeEndString, boolean inGroupBy) {  
        
        query.appendSelect(DATETIMESTAMP_SELECT_NAME_SPARQL);
        if(inGroupBy){
            query.appendGroupBy(DATETIMESTAMP_SELECT_NAME_SPARQL);
        }
        query.appendTriplet(uriSelectNameSparql, Time.hasTime.toString(), TIME_SELECT_NAME_SPARQL, null);
        query.appendTriplet(TIME_SELECT_NAME_SPARQL, Time.inXSDDateTimeStamp.toString(), DATETIMESTAMP_SELECT_NAME_SPARQL, null);
        
        if (searchDateTimeRangeStartString != null || searchDateTimeRangeEndString != null) {
            filterSearchQueryWithDateRangeComparisonWithDateTimeStamp(query, DateFormat.YMDTHMSZZ.toString(), searchDateTimeRangeStartString, searchDateTimeRangeEndString, DATETIMESTAMP_SELECT_NAME_SPARQL);
        }
    }
    
    /**
     * Prepare the event search query
     * @example
     * SELECT DISTINCT  ?uri ?rdfType ?dateTimeStamp 
     * WHERE {
     *   ?uri  <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>  ?rdfType  . 
     *   ?rdfType  <http://www.w3.org/2000/01/rdf-schema#subClassOf>*  <http://www.opensilex.org/vocabulary/oeev#MoveFrom> . 
     *   ?uri  <http://www.opensilex.org/vocabulary/oeev#concerns>  ?concernedItemUri  . 
     *   ?concernedItemUri  <http://www.w3.org/2000/01/rdf-schema#label>  ?concernedItemLabel  . 
     *   ?uri  <http://www.w3.org/2006/time#hasTime>  ?time  . 
     *   ?time  <http://www.w3.org/2006/time#inXSDDateTimeStamp>  ?dateTimeStamp  . 
     *   BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str(?dateTimeStamp)) as ?dateTime) .
     *   BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str("2017-09-08T12:00:00+01:00")) as ?dateRangeStartDateTime) .
     *   BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str("2019-10-08T12:00:00+01:00")) as ?dateRangeEndDateTime) .
     *   FILTER ( (regex (str(?uri), "http://www.phenome-fppn.fr/id/event/96e72788-6bdc-4f8e-abd1-ce9329371e8e", "i")) 
     *    && (regex (?concernedItemLabel, "Plot Lavalette", "i")) 
     *    && (regex (str(?concernedItemUri), "http://www.phenome-fppn.fr/m3p/arch/2017/c17000242", "i")) 
     *    && (?dateRangeStartDateTime <= ?dateTime) && (?dateRangeEndDateTime >= ?dateTime) ) 
     *  }
     *  GROUP BY  ?uri ?rdfType ?dateTimeStamp 
     *  LIMIT 20 
     *  OFFSET 0 
     * @param searchConcernedItemLabel
     * @param searchConcernedItemUri
     * @param dateRangeStartString
     * @param dateRangeEndString
     * @return query
     */
    protected SPARQLQueryBuilder prepareSearchQueryEvents(String uri, String type, String searchConcernedItemLabel, String searchConcernedItemUri, String dateRangeStartString, String dateRangeEndString) {
        SPARQLQueryBuilder query = new SPARQLQueryBuilder();
        query.appendDistinct(Boolean.TRUE);
        
        String uriSelectNameSparql = prepareSearchQueryUri(query, uri, true);
        prepareSearchQueryType(query, uriSelectNameSparql, type, true);  
        prepareSearchQueryConcernedItemFilter(query, uriSelectNameSparql, searchConcernedItemLabel, searchConcernedItemUri); 
        prepareSearchQueryDateTime(query, uriSelectNameSparql, dateRangeStartString, dateRangeEndString, true); 
        
        query.appendLimit(getPageSize());
        query.appendOffset(getPage() * getPageSize());
        
        LOGGER.debug(SPARQL_SELECT_QUERY + query.toString());
        return query;
    }
    
    /**
     * Prepare the event search query
     * @example
     * SELECT  ?uri ?rdfType ?dateTimeStamp 
     * WHERE {
     *   ?uri  <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>  ?rdfType  . 
     *   ?uri  <http://www.w3.org/2006/time#hasTime>  ?time  . 
     *   ?time  <http://www.w3.org/2006/time#inXSDDateTimeStamp>  ?dateTimeStamp  . 
     *   FILTER (regex (str(?uri), "http://opensilex.org/id/event/96e72788-6bdc-4f8e-abd1-ce9329371e8e", "i"))
     *  }
     * @param searchUri
     * @return query
     */
    protected SPARQLQueryBuilder prepareSearchQueryEventDetailed(String searchUri) {
        SPARQLQueryBuilder query = new SPARQLQueryBuilder();
        query.appendDistinct(Boolean.TRUE);
        
        String uriSelectNameSparql = prepareSearchQueryUri(query, searchUri, false);
        prepareSearchQueryType(query, uriSelectNameSparql, null, false);  
        prepareSearchQueryConcernedItemFilter(query, uriSelectNameSparql, null, null); 
        prepareSearchQueryDateTime(query, uriSelectNameSparql, null, null, false); 
        
        LOGGER.debug(SPARQL_SELECT_QUERY + query.toString());
        return query;
    }
    
    /**
     * Prepare the query to search the concerned items of an event
     * @example
     * SELECT DISTINCT  ?concernedItemUri ?concernedItemType 
     * (GROUP_CONCAT(DISTINCT ?concernedItemLabel; SEPARATOR=",") AS ?concernedItemLabels) 
     * WHERE {
     *   <http://opensilex.org/id/event/96e72788-6bdc-4f8e-abd1-ce9329371e8e>  <http://www.opensilex.org/vocabulary/oeev#concerns>  ?concernedItemUri  . 
     *   ?concernedItemUri  <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>  ?concernedItemType  . 
     *   ?concernedItemUri  <http://www.w3.org/2000/01/rdf-schema#label>  ?concernedItemLabel  . 
     * }
     *  GROUP BY  ?concernedItemUri ?concernedItemType 
     * @param eventUri
     * @return query
     */
    protected SPARQLQueryBuilder prepareConcernedItemsSearchQuery(String eventUri) {
        
        SPARQLQueryBuilder query = new SPARQLQueryBuilder();
        query.appendDistinct(Boolean.TRUE);
        
        String uriSelectNameSparql = "<" + eventUri + ">";
        
        query.appendSelect(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL);
        query.appendGroupBy(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL);
        query.appendTriplet(uriSelectNameSparql, Oeev.RELATION_CONCERNS.toString(), CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, null);
        
        query.appendSelect(CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL);
        query.appendGroupBy(CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL);
        query.appendTriplet(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, Rdf.RELATION_TYPE.toString(), CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL, null);
         
        query.appendTriplet(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, Rdfs.RELATION_LABEL.toString(), CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL, null);
        
        query.appendSelectConcat(CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL, SPARQLQueryBuilder.GROUP_CONCAT_SEPARATOR, CONCERNED_ITEM_LABELS_SELECT_NAME_SPARQL);
        
        LOGGER.debug(SPARQL_SELECT_QUERY + query.toString());
        return query;
    }
    
    /**
     * Prepare the query to search the annotations of an event
     * @example
     * SELECT DISTINCT  ?concernedItemUri ?concernedItemType 
     * (GROUP_CONCAT(DISTINCT ?concernedItemLabel; SEPARATOR=",") AS ?concernedItemLabels) 
     * WHERE {
     *   <http://opensilex.org/id/event/96e72788-6bdc-4f8e-abd1-ce9329371e8e>  <http://www.opensilex.org/vocabulary/oeev#concerns>  ?concernedItemUri  . 
     *   ?concernedItemUri  <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>  ?concernedItemType  . 
     *   ?concernedItemUri  <http://www.w3.org/2000/01/rdf-schema#label>  ?concernedItemLabel  . 
     * }
     *  GROUP BY  ?concernedItemUri ?concernedItemType 
     * @param eventUri
     * @return query
     */
    protected SPARQLQueryBuilder prepareAnnotationsSearchQuery(String eventUri) {
        
        SPARQLQueryBuilder query = new SPARQLQueryBuilder();
        query.appendDistinct(Boolean.TRUE);
        
        String uriSelectNameSparql = "<" + eventUri + ">";
        
        query.appendSelect(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL);
        query.appendGroupBy(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL);
        query.appendTriplet(uriSelectNameSparql, Oeev.RELATION_CONCERNS.toString(), CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, null);
        
        query.appendSelect(CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL);
        query.appendGroupBy(CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL);
        query.appendTriplet(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, Rdf.RELATION_TYPE.toString(), CONCERNED_ITEM_TYPE_SELECT_NAME_SPARQL, null);
         
        query.appendTriplet(CONCERNED_ITEM_URI_SELECT_NAME_SPARQL, Rdfs.RELATION_LABEL.toString(), CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL, null);
        
        query.appendSelectConcat(CONCERNED_ITEM_LABEL_SELECT_NAME_SPARQL, SPARQLQueryBuilder.GROUP_CONCAT_SEPARATOR, CONCERNED_ITEM_LABELS_SELECT_NAME_SPARQL);
        
        LOGGER.debug(SPARQL_SELECT_QUERY + query.toString());
        return query;
    }
    
    /**
     * Get an event from a given binding set.
     * @param bindingSet a binding set, result from a search query
     * @return an event target with data extracted from the given binding set
     */
    private Event getEventFromBindingSet(BindingSet bindingSet) {
          
        String eventUri = getValueOfSelectNameFromBindingSet(URI, bindingSet);
                
        String eventType = getValueOfSelectNameFromBindingSet(RDF_TYPE, bindingSet);
        
        String eventDateTimeString = getValueOfSelectNameFromBindingSet(DATETIMESTAMP_SELECT_NAME, bindingSet);    
        DateTime eventDateTime = null;
        if (eventDateTimeString != null) {
            eventDateTime = Dates.stringToDateTimeWithGivenPattern(eventDateTimeString, DateFormat.YMDTHMSZZ.toString());
        }
        
        return new Event(eventUri, eventType, new ArrayList<>(), eventDateTime, new ArrayList<>(), null);
    }
    
    /**
     * Get a concerned item from a binding set
     * @param bindingSet
     * @return concerned item
     */
    private ConcernedItem getConcernedItemFromBindingSet(BindingSet bindingSet) {
                
        String concernedItemUri = getValueOfSelectNameFromBindingSet(CONCERNED_ITEM_URI_SELECT_NAME, bindingSet);
        String concernedItemType = getValueOfSelectNameFromBindingSet(CONCERNED_ITEM_TYPE_SELECT_NAME, bindingSet);
        
        String concernedItemLabelsConcatenated = getValueOfSelectNameFromBindingSet(CONCERNED_ITEM_LABELS_SELECT_NAME, bindingSet);
        ArrayList<String> concernedItemLabels = new ArrayList<>(Arrays.asList(concernedItemLabelsConcatenated.split(SPARQLQueryBuilder.GROUP_CONCAT_SEPARATOR)));

        return new ConcernedItem(concernedItemUri, concernedItemType, concernedItemLabels);
    }
    
    /**
     * Search events stored
     * @param searchUri
     * @param searchType
     * @param searchConcernedItemLabel
     * @param searchConcernedItemUri
     * @param dateRangeStartString
     * @param dateRangeEndString
     * @param searchPage
     * @param searchPageSize
     * @return events
     */
    public ArrayList<Event> searchEvents(String searchUri, String searchType, String searchConcernedItemLabel, String searchConcernedItemUri, String dateRangeStartString, String dateRangeEndString, int searchPage, int searchPageSize) {
        
        setPage(searchPage);
        setPageSize(searchPageSize);
        
        SPARQLQueryBuilder eventsQuery = prepareSearchQueryEvents(searchUri, searchType, searchConcernedItemLabel, searchConcernedItemUri, dateRangeStartString, dateRangeEndString);
        
        // get events from storage
        TupleQuery eventsTupleQuery = getConnection().prepareTupleQuery(QueryLanguage.SPARQL, eventsQuery.toString());
        
        ArrayList<Event> events;
        // for each event, set its properties and concerned Items
        try (TupleQueryResult eventsResult = eventsTupleQuery.evaluate()) {
            events = new ArrayList<>();
            while (eventsResult.hasNext()) {
                Event event = getEventFromBindingSet(eventsResult.next());
                searchEventPropertiesAndSetThemToIt(event);
                searchEventConcernedItemsAndSetThemToIt(event);
                events.add(event);
            }
        }
        return events;
    }
    
    /**
     * Search an event detailed
     * @param searchUri
     * @return events
     */
    public Event searchEventDetailed(String searchUri) {
        
        SPARQLQueryBuilder eventsQuery = prepareSearchQueryEventDetailed(searchUri);
        
        // get events from storage
        TupleQuery eventsTupleQuery = getConnection().prepareTupleQuery(QueryLanguage.SPARQL, eventsQuery.toString());
        
        Event event = null;
        try (TupleQueryResult eventsResult = eventsTupleQuery.evaluate()) {
            if (eventsResult.hasNext()) {
                event = getEventFromBindingSet(eventsResult.next());
                searchEventPropertiesAndSetThemToIt(event);
                searchEventConcernedItemsAndSetThemToIt(event);
                
                //SILEX:todo think about pagination within a widget (like 
                // the annotation one): what should be the best practice?
                // For the moment we use only one page by taking the max value 
                // of page size
                AnnotationDAOSesame annotationDAO = new AnnotationDAOSesame(this.user);
                int pageSizeMaxValue = Integer.parseInt(PropertiesFileManager.getConfigFileProperty("service", "pageSizeMax"));
                ArrayList<Annotation> annotations = annotationDAO.searchAnnotations(null, null, event.getUri(), null, null, 0, pageSizeMaxValue);
                event.setAnnotations(annotations);
                //\SILEX:todo
            }
        }
        return event;
    }
    
    /**
     * Generate an insert query for the given event
     * @param event
     * @return the query
     * @example
     */
    private UpdateRequest prepareInsertQuery(Event event) {
        UpdateBuilder spql = new UpdateBuilder();
        
        Node graph = NodeFactory.createURI(Contexts.EVENTS.toString());
        Resource radiometricTargetUri = ResourceFactory.createResource(event.getUri());
        Node eventConcept = NodeFactory.createURI(Oeev.CONCEPT_EVENT.toString());
        
        spql.addInsert(graph, radiometricTargetUri, RDF.type, eventConcept);
        spql.addInsert(graph, radiometricTargetUri, RDFS.label, event.getLabel());
        
        for (Property property : event.getProperties()) {
            if (property.getValue() != null) {
                org.apache.jena.rdf.model.Property propertyRelation = ResourceFactory.createProperty(property.getRelation());
                
                if (property.getRdfType() != null) {
                    Node propertyValue = NodeFactory.createURI(property.getValue());
                    spql.addInsert(graph, radiometricTargetUri, propertyRelation, propertyValue);
                    spql.addInsert(graph, propertyValue, RDF.type, property.getRdfType());
                } else {
                    Literal propertyValue = ResourceFactory.createStringLiteral(property.getValue());
                    spql.addInsert(graph, radiometricTargetUri, propertyRelation, propertyValue);
                }
            }
        }
        
        UpdateRequest query = spql.buildRequest();
        LOGGER.debug(SPARQL_SELECT_QUERY + " " + query.toString());
        
        return query;
    }
    
    /**
     * Insert the given events in the storage 
     * /!\ Prerequisite: data must have been checked before calling this method
     * @see EventDAOSesame#check(java.util.List) 
     * @param events
     * @return the insertion result, with the error list or the URI of the 
     *         events inserted
     */
    private POSTResultsReturn insert(List<Event> events) {
        List<Status> status = new ArrayList<>();
        List<String> createdResourcesUris = new ArrayList<>();
        
        POSTResultsReturn results;
        boolean resultState = false;
        boolean insert = true;
        
        UriGenerator uriGenerator = new UriGenerator();
        
        getConnection().begin();
        for (Event event : events) {
            try {
                // Generate uri
                event.setUri(uriGenerator.generateNewInstanceUri(Oeev.CONCEPT_EVENT.toString(), null, null));
            } catch (Exception ex) { //In the case of an , no exception should be raised
                insert = false;
            }
            // Insert event
            UpdateRequest query = prepareInsertQuery(event);
            
            try {
                Update prepareUpdate = getConnection().prepareUpdate(QueryLanguage.SPARQL, query.toString());
                prepareUpdate.execute();

                createdResourcesUris.add(event.getUri());
            } catch (RepositoryException ex) {
                    LOGGER.error("Error during commit or rolleback Triplestore statements: ", ex);
            } catch (MalformedQueryException e) {
                    LOGGER.error(e.getMessage(), e);
                    insert = false;
                    status.add(new Status(StatusCodeMsg.QUERY_ERROR, StatusCodeMsg.ERR, StatusCodeMsg.MALFORMED_CREATE_QUERY + " " + e.getMessage()));
            }
        }
        
        if (insert) {
            resultState = true;
            getConnection().commit();
        } else {
            getConnection().rollback();
        }
        
        if (getConnection() != null) {
            getConnection().close();
        }
        
        results = new POSTResultsReturn(resultState, insert, true);
        results.statusList = status;
        results.setCreatedResources(createdResourcesUris);
        if (resultState && !createdResourcesUris.isEmpty()) {
            results.createdResources = createdResourcesUris;
            results.statusList.add(new Status(StatusCodeMsg.RESOURCES_CREATED, StatusCodeMsg.INFO, createdResourcesUris.size() + " " + StatusCodeMsg.RESOURCES_CREATED));
        }
        
        return results;
    }
    
    /**
     * Check and eventually insert events in the storage
     * @param events
     * @return the insertion result :
     *           Error message if errors found in data
     *           The list of the generated URIs events is inserted
     */
    public POSTResultsReturn checkAndInsert(List<Event> events) {
        POSTResultsReturn checkResult = check(events);
        if (checkResult.getDataState()) {
            return insert(events);
        } else { //errors found in data
            return checkResult;
        }
    }
    
    private boolean checkIfEventUriIsValid(String uri) {
        int pageSizeMaxValue = Integer.parseInt(PropertiesFileManager.getConfigFileProperty("service", "pageSizeMax"));
        return !searchEvents(uri, null, null, null, null, null, 0, pageSizeMaxValue).isEmpty();
    }
    
    /**
     * Check the given list of events
     * @param events
     * @return the result with the list of the found errors (empty if no error)
     */
    public POSTResultsReturn check(List<Event> events) {
        POSTResultsReturn checkResult;
        List<Status> status = new ArrayList<>();
        boolean dataIsValid = true;
        
        // 1. Check if user is admin
        UserDaoPhisBrapi userDAO = new UserDaoPhisBrapi();
        if (userDAO.isAdmin(user)) {
            for (Event event : events) {
                String eventUri = event.getUri();
                if (eventUri != null) {
                    // Check the event URI if given (in case of an update)
                    if (!checkIfEventUriIsValid(eventUri)){
                        dataIsValid = false;
                        status.add(new Status(StatusCodeMsg.UNKNOWN_URI, StatusCodeMsg.ERR, 
                                            StatusCodeMsg.UNKNOWN_EVENT_URI + " " + eventUri));
                    }
                }
                
                PropertyDAOSesame propertyDAO = new PropertyDAOSesame();
                for (Property property : event.getProperties()) {
                    // Check if the property exist
                    if (existUri(property.getRelation())) {
                        // Check the domain of the property
                        String eventType = event.getType();
                        if (!propertyDAO.isRelationDomainCompatibleWithRdfType(property.getRelation(), eventType)) {
                            dataIsValid = false;
                            status.add(new Status(StatusCodeMsg.DATA_ERROR, 
                                StatusCodeMsg.ERR, 
                                String.format(StatusCodeMsg.URI_TYPE_NOT_IN_DOMAIN_OF_RELATION, eventType, property.getRelation())));
                        }
                    } else {
                        dataIsValid = false;
                        status.add(new Status(StatusCodeMsg.DATA_ERROR, StatusCodeMsg.ERR, 
                                            StatusCodeMsg.UNKNOWN_URI + " " + property.getRelation()));
                    }
                } 
            }
        } else {
            dataIsValid = false;
            status.add(new Status(StatusCodeMsg.ACCESS_DENIED, StatusCodeMsg.ERR, StatusCodeMsg.ADMINISTRATOR_ONLY));
        }
        
        checkResult = new POSTResultsReturn(dataIsValid, null, dataIsValid);
        checkResult.statusList = status;
        return checkResult;   
    }
    
    /**
     * Search event properties and set them to it
     * @param event 
     */
    private void searchEventPropertiesAndSetThemToIt(Event event) {
        PropertyDAOSesame propertyDAO = new PropertyDAOSesame();
        propertyDAO.getAllPropertiesWithLabelsExceptThoseSpecified(
            event, null, new ArrayList() {
                {
                    add(Rdf.RELATION_TYPE.toString());
                    add(Time.hasTime.toString());
                    add(Oeev.RELATION_CONCERNS.toString());
                }});
    }
    
    /**
     * Search event concerned items and set them to it
     * @param event 
     */
    private void searchEventConcernedItemsAndSetThemToIt(Event event) {
                
        SPARQLQueryBuilder concernedItemsQuery = prepareConcernedItemsSearchQuery(event.getUri());
        TupleQuery concernedItemsTupleQuery = getConnection().prepareTupleQuery(QueryLanguage.SPARQL, concernedItemsQuery.toString());

        try (TupleQueryResult concernedItemsTupleQueryResult = concernedItemsTupleQuery.evaluate()) {
            ConcernedItem concernedItem;
            while(concernedItemsTupleQueryResult.hasNext()) {
                concernedItem = getConcernedItemFromBindingSet(concernedItemsTupleQueryResult.next());
                event.addConcernedItem(concernedItem);
            }
        }
    }
    
    /**
     * Get the value of a name in the SELECT statement from a binding set
     * @param selectName 
     * @param bindingSet 
     */
    private String getValueOfSelectNameFromBindingSet(String selectName, BindingSet bindingSet) { 
        Value selectedFieldValue = bindingSet.getValue(selectName);
        if (selectedFieldValue != null) {
            return selectedFieldValue.stringValue();
        }
        return null;
    }

    /**
     * Generate a query to count the results of the research with the 
     * searched parameters. 
     * @example 
     * SELECT DISTINCT  (COUNT(DISTINCT ?uri) AS ?count) 
     * WHERE {
     *   ?uri  <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>  ?rdfType  . 
     *   ?rdfType  <http://www.w3.org/2000/01/rdf-schema#subClassOf>*  <http://www.opensilex.org/vocabulary/oeev#MoveFrom> . 
     *   ?uri  <http://www.opensilex.org/vocabulary/oeev#concerns>  ?concernedItemUri  . 
     *   ?concernedItemUri  <http://www.w3.org/2000/01/rdf-schema#label>  ?concernedItemLabel  . 
     *   ?uri  <http://www.w3.org/2006/time#hasTime>  ?time  . 
     *   ?time  <http://www.w3.org/2006/time#inXSDDateTimeStamp>  ?dateTimeStamp  . 
     *   BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str(?dateTimeStamp)) as ?dateTime) .
     *   BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str("2017-09-08T12:00:00+01:00")) as ?dateRangeStartDateTime) .
     *   BIND(<http://www.w3.org/2001/XMLSchema#dateTime>(str("2019-10-08T12:00:00+01:00")) as ?dateRangeEndDateTime) .
     *   FILTER ( (regex (str(?uri), "http://www.phenome-fppn.fr/id/event/96e72788-6bdc-4f8e-abd1-ce9329371e8e", "i")) 
     *     && (regex (?concernedItemLabel, "Plot Lavalette", "i")) 
     *     && (regex (str(?concernedItemUri), "http://www.phenome-fppn.fr/m3p/arch/2017/c17000242", "i")) 
     *     && (?dateRangeStartDateTime <= ?dateTime) && (?dateRangeEndDateTime >= ?dateTime) ) 
     * }
     */
    private SPARQLQueryBuilder prepareCountQuery(String searchUri, String searchType, String searchConcernedItemLabel, String searchConcernedItemUri, String dateRangeStartString, String dateRangeEndString) {
        SPARQLQueryBuilder query = this.prepareSearchQueryEvents(searchUri, searchType, searchConcernedItemLabel, searchConcernedItemUri, dateRangeStartString, dateRangeEndString);
        query.clearSelect();
        query.clearLimit();
        query.clearOffset();
        query.clearGroupBy();
        query.appendSelect("(COUNT(DISTINCT " + URI_SELECT_NAME_SPARQL + ") AS " + "?" + COUNT_ELEMENT_QUERY + ")");
        LOGGER.debug(SPARQL_SELECT_QUERY + " " + query.toString());
        return query;
    }

    /**
     * Count the total number of events filtered with the search fields
     * @param searchUri
     * @param searchType
     * @param searchConcernedItemLabel
     * @param searchConcernedItemUri
     * @param dateRangeStartString
     * @param dateRangeEndString
     * @return results number
     * @throws RepositoryException
     * @throws MalformedQueryException
     * @throws QueryEvaluationException
     */
    public Integer count(String searchUri, String searchType, String searchConcernedItemLabel, String searchConcernedItemUri, String dateRangeStartString, String dateRangeEndString) 
            throws RepositoryException, MalformedQueryException, QueryEvaluationException {
        
        SPARQLQueryBuilder countQuery = prepareCountQuery(searchUri, searchType, searchConcernedItemLabel, searchConcernedItemUri, dateRangeStartString, dateRangeEndString);
        
        TupleQuery tupleQuery = getConnection().prepareTupleQuery(QueryLanguage.SPARQL, countQuery.toString());
        Integer count = 0;
        try (TupleQueryResult result = tupleQuery.evaluate()) {
            if (result.hasNext()) {
                BindingSet bindingSet = result.next();
                count = Integer.parseInt(bindingSet.getValue(COUNT_ELEMENT_QUERY).stringValue());
            }
        }
        return count;
    }

    @Override
    protected SPARQLQueryBuilder prepareSearchQuery() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Integer count() throws RepositoryException, MalformedQueryException, QueryEvaluationException {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
