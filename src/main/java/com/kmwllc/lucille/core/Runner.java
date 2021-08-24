package com.kmwllc.lucille.core;

import com.kmwllc.lucille.message.MessageManagerFactory;
import com.kmwllc.lucille.message.RunnerMessageManager;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Invokes one or more Connectors in sequence, only starting the next Connector once all the work
 * generated by the previous Connector is fully complete.
 *
 */
public class Runner {

  private static final Logger log = LoggerFactory.getLogger(Runner.class);

  private final String runId;
  private final RunnerMessageManager runnerMessageManager;
  private final Config config;

  public static void main(String[] args) throws Exception {
    //MessageManagerFactory.getInstance().setLocalMode();
    new Runner().runConnectors(true);
  }

  public Runner() throws Exception {
    this(ConfigAccessor.loadConfig());
  }

  public Runner(Config config) throws Exception {
    this.config = config;
    // generate a unique ID for this run
    this.runId = UUID.randomUUID().toString();
    log.info("runId=" + runId);
    this.runnerMessageManager = MessageManagerFactory.getInstance().getRunnerMessageManager(runId);
  }

  public String getRunId() {
    return runId;
  }

  public void runConnectors(boolean startWorkerAndIndexer) throws Exception {

    Worker worker = null;
    Indexer indexer = null;

    if (startWorkerAndIndexer) {
      indexer = new Indexer(config);
      worker = new Worker(config);
      Thread workerThread = new Thread(worker);
      Thread indexerThread = new Thread(indexer);
      indexerThread.start();
      workerThread.start();
    }

    List<Connector> connectors = Connector.fromConfig(config);

    // run all the connectors in sequence, only starting the next connector once all the work
    // generated by the previous connector has been completed
    for (Connector connector : connectors) {
      runConnector(connector);
    }

    if (indexer!=null) {
      indexer.terminate();
    }

    if (worker!=null) {
      worker.terminate();
    }

    runnerMessageManager.close();
  }

  public void runConnector(Connector connector) throws Exception {

    log.info("Running connector: " + connector.toString());

    Publisher publisher = new Publisher(runId);

    Thread connectorThread = new Thread(new Runnable() {
      @Override
      public void run() {
        connector.start(publisher);
      }
    });
    connectorThread.start();
    // TODO: what if it didn't start properly?

    // TODO: CONSIDER MOVING THIS INTO PUBLISHER; call publisher.waitForCompletion();
    // might want implementation of publisher that doesn't care about completion
    while (true) {
      Event event = runnerMessageManager.pollEvent();

      if (event !=null) {
        publisher.handleEvent(event);
      }

      // TODO: timeouts

      // We are done if 1) the Connector has terminated and therefore no more Documents will be generated,
      // 2) the Publisher has accounted for all published Documents and their children,
      // 3) there are no more Events relating to this run to consume
      if (!connectorThread.isAlive() && publisher.isReconciled() && !runnerMessageManager.hasEvents(runId)) {
        break;
      }

      log.info("waiting on " + publisher.countPendingDocuments() + " documents");
    }

    log.info("Work complete");
  }

}
