package com.kmwllc.lucille.core;

/**
 * Responsible for managing a "run." A run is a sequential execution of one or more Connectors.
 * During a run, all the work generated by one Connector must be complete before the next Connector begins.
 * A Runner should only stop once the entire run is complete.
 *
 * Runner instances are not meant to be shared across runs; a new Runner instance should be created for each run
 * and it should generate a run ID upon creation.
 */
public interface Runner {

  /**
   * Returns the ID for the current run.
   * @return
   */
  String getRunId();

  /**
   * Runs all of the Connectors specified in the Config provided to Runner at construction time.
   *
   * @param startWorkerAndIndexer true if the Runner should start Worker and Indexer threads
   */
  void runConnectors(boolean startWorkerAndIndexer) throws Exception;

  /**
   * Runs the designated Connector, only returning when 1) the Connector has finished generating documents, and
   * 2) all of the documents (and any generated children) have reached an end state in the workflow:
   * either being indexed or erroring-out.
   */
  void runConnector(Connector connector) throws Exception;
}
