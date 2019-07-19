package de.digitalcollections.solrocr.solr;

import de.digitalcollections.solrocr.formats.OcrBlock;
import de.digitalcollections.solrocr.formats.OcrFormat;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.lucene.OcrHighlighter;
import de.digitalcollections.solrocr.util.OcrHighlightResult;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.Query;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrOcrHighlighter.class);

  private OcrFormat ocrFormat;
  private Set<String> ocrFieldNames;


  public SolrOcrHighlighter(OcrFormat ocrFormat, List<String> ocrFieldNames) {
    this.ocrFormat = ocrFormat;
    this.ocrFieldNames = new HashSet<>(ocrFieldNames);
  }

  @Override
  public NamedList<Object> doHighlighting(DocList docs, Query query, SolrQueryRequest req, String[] defaultFields)
      throws IOException {
    // Copied from superclass
    // - *snip* -
    final SolrParams params = req.getParams();
    if (!isHighlightingEnabled(params)) {
      return null;
    }
    if (docs.size() == 0) {
      return new SimpleOrderedMap<>();
    }
    int[] docIDs = toDocIDs(docs);
    String[] keys = getUniqueKeys(req.getSearcher(), docIDs);
    // - *snap* -

    // query-time parameters
    String[] ocrFieldNames = getOcrHighlightFields(query, req, defaultFields);
    int[] maxPassagesOcr = getMaxPassages(ocrFieldNames, params);

    Map<String, String> highlightFieldWarnings = new HashMap<>();
    OcrHighlightResult[] ocrSnippets = null;
    // Highlight OCR fields
    if (ocrFieldNames.length > 0) {
      OcrHighlighter ocrHighlighter = new OcrHighlighter(
          req.getSearcher(), req.getSchema().getIndexAnalyzer(), req.getParams());
      String limitBlock = params.get(OcrHighlightParams.LIMIT_BLOCK, "block").toUpperCase();
      BreakIterator ocrBreakIterator = ocrFormat.getBreakIterator(
          OcrBlock.valueOf(params.get(OcrHighlightParams.CONTEXT_BLOCK, "line").toUpperCase()),
          limitBlock.equals("NONE") ? null : OcrBlock.valueOf(limitBlock),
          params.getInt(OcrHighlightParams.CONTEXT_SIZE, 2));
      OcrPassageFormatter ocrFormatter = ocrFormat.getPassageFormatter(
          params.get(HighlightParams.TAG_PRE, "<em>"),
          params.get(HighlightParams.TAG_POST, "</em>"),
          params.getBool(OcrHighlightParams.ABSOLUTE_HIGHLIGHTS, false));
      ocrSnippets = ocrHighlighter.highlightOcrFields(
          ocrFieldNames, query, docIDs, maxPassagesOcr, ocrBreakIterator, ocrFormatter,
          params.get(OcrHighlightParams.PAGE_ID, null));
    }

    // Assemble output data
    SimpleOrderedMap out = new SimpleOrderedMap();
    if (ocrSnippets != null) {
      this.addOcrSnippets(out, keys, ocrFieldNames, ocrSnippets);
    }
    return out;
  }

  private int[] getMaxPassages(String[] fieldNames, SolrParams params) {
    int[] maxPassages = new int[fieldNames.length];
    for (int i = 0; i < fieldNames.length; i++) {
      maxPassages[i] = params.getFieldInt(fieldNames[i], HighlightParams.SNIPPETS, 1);
    }
    return maxPassages;
  }

  private void addOcrSnippets(NamedList<Object> out, String[] keys, String[] ocrFieldNames,
                              OcrHighlightResult[] ocrSnippets) {
    for (int k=0; k < keys.length; k++) {
      String docId = keys[k];
      SimpleOrderedMap docMap = (SimpleOrderedMap) out.get(docId);
      if (docMap == null) {
        docMap = new SimpleOrderedMap();
        out.add(docId, docMap);
      }
      if (ocrSnippets[k] == null) {
        continue;
      }
      docMap.addAll(ocrSnippets[k].toNamedList());
    }
  }

  /** Obtain all fields among the requested fields that contain OCR data. */
  private String[] getOcrHighlightFields(Query query, SolrQueryRequest req, String[] defaultFields) {
    return Arrays.stream(getHighlightFields(query, req, defaultFields))
        .distinct()
        .filter(ocrFieldNames::contains)
        .toArray(String[]::new);
  }
}
