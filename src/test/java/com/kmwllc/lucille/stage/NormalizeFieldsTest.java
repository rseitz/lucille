package com.kmwllc.lucille.stage;

import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Stage;
import com.kmwllc.lucille.core.StageException;
import org.junit.Test;

import static org.junit.Assert.*;

public class NormalizeFieldsTest {

  StageFactory factory = StageFactory.of(NormalizeFields.class);

  @Test
  public void testNormalizeFields() throws StageException {
    Stage stage = factory.get("FieldNormalizerTest/config.conf");

    Document doc1 = new Document("doc1");
    doc1.setField("input1", "test1");
    doc1.setField("input 2", "test2");
    doc1.setField("input 3 (test123)", "test3");
    doc1.setField(".sonarcontent", "content");
    stage.processDocument(doc1);
    assertTrue(doc1.has("input1"));
    assertFalse(doc1.has("input 2"));
    assertTrue(doc1.has("input_2"));
    assertFalse(doc1.has("input 3 (test123)"));
    assertTrue(doc1.has("input_3_test123"));
    assertTrue(doc1.has(".sonarcontent"));
  }
}
