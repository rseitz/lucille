package com.kmwllc.lucille.indexer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Event;
import com.kmwllc.lucille.core.Event.Type;
import com.kmwllc.lucille.core.IndexerException;
import com.kmwllc.lucille.core.KafkaDocument;
import com.kmwllc.lucille.message.IndexerMessenger;
import com.kmwllc.lucille.message.TestMessenger;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.transport.endpoints.BooleanResponse;

public class OpenSearchIndexerTest {

  private OpenSearchClient mockClient;

  @Before
  public void setup() throws IOException {
    setupOpenSearchClient();
  }

  private void setupOpenSearchClient() throws IOException {
    mockClient = Mockito.mock(OpenSearchClient.class);

    BooleanResponse mockBooleanResponse = Mockito.mock(BooleanResponse.class);
    Mockito.when(mockClient.ping()).thenReturn(mockBooleanResponse);

    // make first call to validateConnection succeed but subsequent calls to fail
    Mockito.when(mockBooleanResponse.value()).thenReturn(true, false);

    BulkRequest.Builder mockRequestBuilder = Mockito.mock(BulkRequest.Builder.class);
    BulkResponse mockResponse = Mockito.mock(BulkResponse.class);
    BulkRequest mockBulkRequest = Mockito.mock(BulkRequest.class);
    Mockito.when(mockRequestBuilder.build()).thenReturn(mockBulkRequest);
    Mockito.when(mockClient.bulk(ArgumentMatchers.any(BulkRequest.class))).thenReturn(mockResponse);
  }

  /**
   * Tests that the indexer correctly polls completed documents from the destination topic and sends them to OpenSearch.
   *
   * @throws Exception
   */
  @Test
  public void testOpenSearchIndexer() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/config.conf");

    Document doc = Document.create("doc1", "test_run");
    Document doc2 = Document.create("doc2", "test_run");

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    messenger.sendCompleted(doc2);
    indexer.run(2);

    Assert.assertEquals(2, messenger.getSavedEvents().size());

    List<Event> events = messenger.getSavedEvents();
    for (int i = 1; i <= events.size(); i++) {
      Assert.assertEquals("doc" + i, events.get(i - 1).getDocumentId());
      Assert.assertEquals(Event.Type.FINISH, events.get(i - 1).getType());
    }
  }

  @Test
  public void testOpenSearchIndexerException() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/exception.conf");

    Document doc = Document.create("doc1", "test_run");
    Document doc2 = Document.create("doc2", "test_run");
    Document doc3 = Document.create("doc3", "test_run");
    Document doc4 = Document.create("doc4", "test_run");
    Document doc5 = Document.create("doc5", "test_run");

    OpenSearchIndexer indexer = new ErroringOpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    messenger.sendCompleted(doc2);
    messenger.sendCompleted(doc3);
    messenger.sendCompleted(doc4);
    messenger.sendCompleted(doc5);
    indexer.run(5);

    List<Event> events = messenger.getSavedEvents();
    Assert.assertEquals(5, events.size());
    for (int i = 1; i <= events.size(); i++) {
      Assert.assertEquals("doc" + i, events.get(i - 1).getDocumentId());
      Assert.assertEquals(Event.Type.FAIL, events.get(i - 1).getType());
    }
  }

  @Test
  public void testValidateConnection() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/config.conf");
    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    Assert.assertTrue(indexer.validateConnection()); // should only work the first time with the mockClient
    Assert.assertFalse(indexer.validateConnection());
    Assert.assertFalse(indexer.validateConnection());

  }

  @Test
  public void testMultipleBatches() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/batching.conf");
    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");

    Document doc = Document.create("doc1", "test_run");
    Document doc2 = Document.create("doc2", "test_run");
    Document doc3 = Document.create("doc3", "test_run");
    Document doc4 = Document.create("doc4", "test_run");
    Document doc5 = Document.create("doc5", "test_run");

    messenger.sendCompleted(doc);
    messenger.sendCompleted(doc2);
    messenger.sendCompleted(doc3);
    messenger.sendCompleted(doc4);
    messenger.sendCompleted(doc5);
    indexer.run(5);

    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(
        BulkRequest.class);

    verify(mockClient, times(3)).bulk(bulkRequestArgumentCaptor.capture());

    List<BulkRequest> bulkRequestValue = bulkRequestArgumentCaptor.getAllValues();
    assertEquals(3, bulkRequestValue.size());

    BulkRequest br = bulkRequestArgumentCaptor.getValue();
    List<BulkOperation> requests = br.operations();
    IndexOperation indexRequest = requests.get(0).index();

    assertEquals(doc5.getId(), indexRequest.id());

    Assert.assertEquals(5, messenger.getSavedEvents().size());

    List<Event> events = messenger.getSavedEvents();
    Assert.assertEquals("doc1", events.get(0).getDocumentId());
    Assert.assertEquals(Event.Type.FINISH, events.get(0).getType());
  }

  @Test
  public void testOpenSearchIndexerNestedJson() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/config.conf");

    Document doc = Document.create("doc1", "test_run");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode jsonNode = mapper.readTree("{\"a\": [{\"aa\":1}, {\"aa\": 2}] }");
    doc.setField("myJsonField", jsonNode);

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    indexer.run(1);

    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);

    verify(mockClient, times(1)).bulk(bulkRequestArgumentCaptor.capture());

    BulkRequest br = bulkRequestArgumentCaptor.getValue();
    List<BulkOperation> requests = br.operations();
    IndexOperation indexRequest = requests.get(0).index();

    Map<String, Object> map = (Map<String, Object>) indexRequest.document();

    assertEquals(doc.getId(), indexRequest.id());
    assertEquals(doc.asMap().get("myJsonField"), map.get("myJsonField"));

    Assert.assertEquals(1, messenger.getSavedEvents().size());

    List<Event> events = messenger.getSavedEvents();
    Assert.assertEquals("doc1", events.get(0).getDocumentId());
    Assert.assertEquals(Event.Type.FINISH, events.get(0).getType());
  }

  @Test
  public void testOpenSearchIndexerNestedJsonMultivalued() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/config.conf");

    Document doc = Document.create("doc1", "test_run");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode jsonNode = mapper.readTree("{\"a\": [{\"aa\":1}, {\"aa\": 2}] }");
    doc.setField("myJsonField", jsonNode);

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    indexer.run(1);

    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);

    verify(mockClient, times(1)).bulk(bulkRequestArgumentCaptor.capture());

    BulkRequest br = bulkRequestArgumentCaptor.getValue();
    List<BulkOperation> requests = br.operations();
    IndexOperation indexRequest = requests.get(0).index();
    Map<String, Object> map = (Map<String, Object>) indexRequest.document();

    assertEquals(doc.getId(), map.get("id"));
    assertEquals(doc.asMap().get("myJsonField"), map.get("myJsonField"));

    Assert.assertEquals(1, messenger.getSavedEvents().size());

    List<Event> events = messenger.getSavedEvents();
    Assert.assertEquals("doc1", events.get(0).getDocumentId());
    Assert.assertEquals(Event.Type.FINISH, events.get(0).getType());
  }

  @Test
  public void testOpenSearchIndexerNestedJsonWithObjects() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/config.conf");

    Document doc = Document.create("doc1", "test_run");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode jsonNode = mapper.readTree("{\"a\": {\"aa\":1}, \"b\":{\"ab\": 2} }");
    doc.setField("myJsonField", jsonNode);

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    indexer.run(1);

    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);

    verify(mockClient, times(1)).bulk(bulkRequestArgumentCaptor.capture());

    BulkRequest br = bulkRequestArgumentCaptor.getValue();
    List<BulkOperation> requests = br.operations();
    IndexOperation indexRequest = requests.get(0).index();
    Map<String, Object> map = (Map<String, Object>) indexRequest.document();

    assertEquals(doc.getId(), map.get("id"));
    assertEquals(doc.asMap().get("myJsonField"), map.get("myJsonField"));

    Assert.assertEquals(1, messenger.getSavedEvents().size());

    List<Event> events = messenger.getSavedEvents();
    Assert.assertEquals("doc1", events.get(0).getDocumentId());
    Assert.assertEquals(Event.Type.FINISH, events.get(0).getType());
  }

  @Test
  public void testRouting() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/routing.conf");

    Document doc = Document.create("doc1");
    doc.setField("routing", "routing1");
    doc.setField("field1", "value1");

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    indexer.run(1);

    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);

    verify(mockClient, times(1)).bulk(bulkRequestArgumentCaptor.capture());

    BulkRequest br = bulkRequestArgumentCaptor.getValue();
    List<BulkOperation> requests = br.operations();
    IndexOperation indexRequest = requests.get(0).index();
    Map<String, Object> map = (Map<String, Object>) indexRequest.document();

    assertEquals("doc1", indexRequest.id());
    assertEquals("routing1", indexRequest.routing());
    assertEquals(Map.of("field1", "value1", "id", "doc1", "routing", "routing1"), map);
  }

  @Test
  public void testDocumentVersioning() throws Exception {
    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/versioning.conf");

    KafkaDocument doc = new KafkaDocument(
        new ObjectMapper().createObjectNode()
            .put("id", "doc1")
            .put("field1", "value1"));
    doc.setKafkaMetadata(new ConsumerRecord<>("testing", 0, 100, null, null));

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient, "testing");
    messenger.sendCompleted(doc);
    indexer.run(1);
    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);

    verify(mockClient, times(1)).bulk(bulkRequestArgumentCaptor.capture());

    BulkRequest br = bulkRequestArgumentCaptor.getValue();
    List<BulkOperation> requests = br.operations();
    IndexOperation indexRequest = requests.get(0).index();
    Map<String, Object> map = (Map<String, Object>) indexRequest.document();

    Long expectedVersion = Long.valueOf(100);
    assertEquals("doc1", indexRequest.id());
    assertEquals(expectedVersion, indexRequest.version());
    assertEquals(VersionType.ExternalGte, indexRequest.versionType());
    assertEquals(Map.of("id", "doc1", "field1", "value1"), map);
  }

  @Test
  public void testBulkResponseErroring() throws Exception {
    OpenSearchClient mockClient2 = Mockito.mock(OpenSearchClient.class);

    BooleanResponse mockBooleanResponse = Mockito.mock(BooleanResponse.class);
    Mockito.when(mockClient2.ping()).thenReturn(mockBooleanResponse);

    // make first call to validateConnection succeed but subsequent calls to fail
    Mockito.when(mockBooleanResponse.value()).thenReturn(true, false);

    BulkRequest.Builder mockRequestBuilder = Mockito.mock(BulkRequest.Builder.class);
    BulkResponse mockResponse = Mockito.mock(BulkResponse.class);
    BulkRequest mockBulkRequest = Mockito.mock(BulkRequest.class);
    Mockito.when(mockRequestBuilder.build()).thenReturn(mockBulkRequest);
    Mockito.when(mockClient2.bulk(ArgumentMatchers.any(BulkRequest.class))).thenReturn(mockResponse);

    // mocking for the bulk response items and error causes
    BulkResponseItem.Builder mockItemBuilder = Mockito.mock(BulkResponseItem.Builder.class);
    BulkResponseItem mockItemError = Mockito.mock(BulkResponseItem.class);
    BulkResponseItem mockItemNoError = Mockito.mock(BulkResponseItem.class);
    ErrorCause mockError = new ErrorCause.Builder().reason("mock reason").type("mock-type").build();
    Mockito.when(mockItemBuilder.build()).thenReturn(mockItemError);
    Mockito.when(mockItemError.error()).thenReturn(mockError);

    List<BulkResponseItem> bulkResponseItems = Arrays.asList(mockItemNoError, mockItemError, mockItemNoError);
    Mockito.when(mockResponse.items()).thenReturn(bulkResponseItems);

    TestMessenger messenger = new TestMessenger();
    Config config = ConfigFactory.load("OpenSearchIndexerTest/config.conf");

    Document doc = Document.create("doc1", "test_run");
    Document doc2 = Document.create("doc2", "test_run");
    Document doc3 = Document.create("doc3", "test_run");

    OpenSearchIndexer indexer = new OpenSearchIndexer(config, messenger, mockClient2, "testing");
    messenger.sendCompleted(doc);
    messenger.sendCompleted(doc2);
    messenger.sendCompleted(doc3);

    indexer.run(3);

    IndexerException exc = assertThrows(IndexerException.class, () -> indexer.sendToIndex(Arrays.asList(doc, doc2, doc3)));
    assertEquals("mock reason", exc.getMessage());

    List<Event> events = messenger.getSavedEvents();
    assertEquals(3, events.size());
    for (int i = 1; i <= events.size(); i++) {
      Assert.assertEquals("doc" + i, events.get(i - 1).getDocumentId());
      Assert.assertEquals(Type.FAIL, events.get(i - 1).getType());
    }
  }

  private static class ErroringOpenSearchIndexer extends OpenSearchIndexer {

    public ErroringOpenSearchIndexer(Config config, IndexerMessenger messenger,
        OpenSearchClient client, String metricsPrefix) {
      super(config, messenger, client, "testing");
    }

    @Override
    public void sendToIndex(List<Document> docs) throws Exception {
      throw new Exception("Test that errors when sending to indexer are correctly handled");
    }
  }
}
