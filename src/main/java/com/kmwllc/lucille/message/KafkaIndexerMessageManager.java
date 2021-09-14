package com.kmwllc.lucille.message;

import com.kmwllc.lucille.core.Event;
import com.kmwllc.lucille.core.Document;
import com.typesafe.config.Config;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class KafkaIndexerMessageManager implements IndexerMessageManager {

  public static final Logger log = LoggerFactory.getLogger(KafkaIndexerMessageManager.class);
  private final Consumer<String, String> destConsumer;
  private final KafkaProducer<String, String> kafkaProducer;
  private final String pipelineName;

  public KafkaIndexerMessageManager(Config config, String pipelineName) {
    this.pipelineName = pipelineName;
    Properties consumerProps = KafkaUtils.createConsumerProps(config);
    consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, "lucille-indexer-" + pipelineName);
    this.destConsumer = new KafkaConsumer(consumerProps);
    this.destConsumer.subscribe(Collections.singletonList(KafkaUtils.getDestTopicName(pipelineName)));

    this.kafkaProducer = KafkaUtils.createProducer(config);
  }

  /**
   * Polls for a document that has been processed by the pipeine and is waiting to be indexed.
   */
  @Override
  public Document pollCompleted() throws Exception {
    ConsumerRecords<String, String> consumerRecords = destConsumer.poll(KafkaUtils.POLL_INTERVAL);
    if (consumerRecords.count() > 0) {
      destConsumer.commitSync();
      ConsumerRecord<String, String> record = consumerRecords.iterator().next();
      return Document.fromJsonString(record.value());
    }
    return null;
  }

  /**
   * Sends an Event relating to a Document to the appropriate location for Events.
   *
   */
  @Override
  public void sendEvent(Event event) throws Exception {
    String confirmationTopicName = KafkaUtils.getEventTopicName(pipelineName, event.getRunId());
    RecordMetadata result = (RecordMetadata)  kafkaProducer.send(
      new ProducerRecord(confirmationTopicName, event.getDocumentId(), event.toString())).get();
    kafkaProducer.flush();
  }

  @Override
  public void close() throws Exception {
    destConsumer.close();
  }

}
