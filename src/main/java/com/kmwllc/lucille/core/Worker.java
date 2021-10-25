package com.kmwllc.lucille.core;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.kmwllc.lucille.message.WorkerMessageManager;
import com.kmwllc.lucille.message.WorkerMessageManagerFactory;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

class Worker implements Runnable {

  public static final int TIMEOUT_CHECK_MS = 1000;

  private static final Logger log = LoggerFactory.getLogger(Worker.class);
  private final WorkerMessageManager manager;

  private final Pipeline pipeline;

  private volatile boolean running = true;

  private final MetricRegistry metrics;
  private final Meter meter;
  private final AtomicReference<Instant> pollInstant;

  private boolean trackRetries = false;
  private RetryCounter counter = null;

  public void terminate() {
    log.info("terminate called");
    running = false;
  }

  public Worker(Config config, WorkerMessageManager manager, String pipelineName) throws Exception {
    this.manager = manager;
    this.pipeline = Pipeline.fromConfig(config, pipelineName);
    this.metrics = SharedMetricRegistries.getOrCreate("default");
    this.meter = metrics.meter("worker.meter");
    if (config.hasPath("worker.maxRetries")) {
      log.info("Retries will be tracked in Zookeeper with a configured maximum of: " + config.getInt("worker.maxRetries"));
      this.trackRetries = true;
      this.counter = new ZKRetryCounter(config);
    }
    this.pollInstant = new AtomicReference();
    this.pollInstant.set(Instant.now());
  }

  @Override
  public void run() {
    meter.mark(0);

    // Timer to log a status message every five minutes
    Timer logTimer = new Timer();
    logTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        log.info(String.format("Workers are currently processing documents at a rate of %f documents/second. " +
            "%d documents have been processed so far.", meter.getFiveMinuteRate(), meter.getCount()));
      }
    }, 5000, 5000);

    while (running) {

      Document doc;
      try {
        pollInstant.set(Instant.now());
        doc = manager.pollDocToProcess();
      } catch (Exception e) {
        log.info("interrupted " + e);
        terminate();
        return;
      }

      if (doc == null) {
        continue;
      }

      if (trackRetries && counter.add(doc)) {
        try {
          log.info("Retry count exceeded for document " + doc.getId() + "; Sending to failure topic");
          manager.sendFailed(doc);
        } catch (Exception e) {
          log.error("Failed to send doc to failure topic: " + doc.getId(), e);
        }

        try {
          Event event = new Event(doc.getId(), doc.getRunId(), "SENT_TO_DLQ", Event.Type.FAIL);
          manager.sendEvent(event);
        } catch (Exception e) {
          log.error("Failed to send completion event for: " + doc.getId(), e);
        }

        commitOffsetsAndRemoveCounter(doc);
        continue;
      }

      List<Document> results = null;
      try {
        results = pipeline.processDocument(doc);
        meter.mark();
      } catch (Exception e) {
        log.error("Error processing document: " + doc.getId(), e);
        try {
          manager.sendEvent(new Event(doc.getId(), doc.getString("run_id"), null, Event.Type.FAIL));
        } catch (Exception e2) {
          log.error("Error sending failure event for document: " + doc.getId(), e2);
        }

        commitOffsetsAndRemoveCounter(doc);

        return;
      }

      try {
        // send CREATE events for any children document generated by the pipeline;
        // Note: we want to make sure that the Runner is notified of any generated children
        // BEFORE the parent document is completed. This prevents a situation where the Runner
        // assumes the run is complete because the parent is complete and the Runner didn't know
        // about the children.
        for (Document result : results) {
          // a document is a child if it has a different ID from the initial document
          if (!doc.getId().equals(result.getId())) {
            manager.sendEvent(new Event(result.getId(),
                doc.getString("run_id"), null, Event.Type.CREATE));
          }
        }

        // send the initial document and any children to the topic for processed documents
        for (Document result : results) {
          manager.sendCompleted(result);
        }
      } catch (Exception e) {
        log.error("Messaging error after processing document: " + doc.getId(), e);
      }

      commitOffsetsAndRemoveCounter(doc);

      /*ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
      if (now.getMinute() % 5 == 0 && !loggedThisMinute) {
        log.info(String.format("Workers are currently processing documents at a rate of %f documents/second. " +
            "%d documents have been processed so far.", meter.getFiveMinuteRate(), meter.getCount()));
        loggedThisMinute = true;
      } else if (now.getMinute() % 6 == 0) {
        loggedThisMinute = false;
      }*/
    }

      try {
        manager.close();
      } catch (Exception e) {
        log.error("Error closing message manager", e);
      }

      try {
        pipeline.stopStages();
      } catch (StageException e) {
        log.error("Error stopping pipeline stage", e);
      }

      logTimer.cancel();

      log.info("Exiting");
  }

  private void commitOffsetsAndRemoveCounter(Document doc) {
    try {
      manager.commitPendingDocOffsets();
      if (trackRetries) {
        counter.remove(doc);
      }
    } catch (Exception commitException) {
      log.error("Error committing updated offsets for pending documents", commitException);
    }
  }

  public AtomicReference<Instant> getPreviousPollInstant() {
    return pollInstant;
  }

  private static void spawnWatcher(Worker worker, int maxProcessingSecs) {
    Executors.newSingleThreadExecutor().submit(new Runnable() {
      public void run() {
        while (true) {
          if (Duration.between(worker.getPreviousPollInstant().get(), Instant.now()).getSeconds() > maxProcessingSecs) {
            log.error("Shutting down because maximum allowed time between previous poll is exceeded.");
            System.exit(1);
          }
          try {
            Thread.sleep(TIMEOUT_CHECK_MS);
          } catch (InterruptedException e) {
            log.error("Watcher thread interrupted");
            return;
          }
        }
      }
    });
  }

  public static WorkerThread startThread(Config config, WorkerMessageManager manager, String pipelineName) throws
      Exception {
    Worker worker = new Worker(config, manager, pipelineName);
    WorkerThread workerThread = new WorkerThread(worker);
    workerThread.start();
    return workerThread;
  }

  public static void main(String[] args) throws Exception {
    Config config = ConfigUtils.loadConfig();
    String pipelineName = args.length > 0 ? args[0] : config.getString("worker.pipeline");
    log.info("Starting Workers for pipeline: " + pipelineName);

    WorkerMessageManagerFactory workerMessageManagerFactory =
        WorkerMessageManagerFactory.getKafkaFactory(config, pipelineName);

    WorkerPool workerPool = new WorkerPool(config, pipelineName, workerMessageManagerFactory);
    workerPool.start();

    Signal.handle(new Signal("INT"), signal -> {
      workerPool.stop();
      log.info("Workers shutting down");
      try {
        workerPool.join();
      } catch (InterruptedException e) {
        log.error("Interrupted", e);
      }
      System.exit(0);
    });
  }

}
