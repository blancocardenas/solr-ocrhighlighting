package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.uhighlight.FieldHighlighter;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.OffsetsEnum;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.util.BytesRef;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.lucene.byteoffset.ByteOffsetsEnum;
import org.mdz.search.solrocr.lucene.byteoffset.FieldByteOffsetStrategy;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.IterableCharSequence.OffsetType;

/**
 * A customization of {@link FieldHighlighter} to support lazy-loaded field values and byte offsets from payloads.
 */
public class OcrFieldHighlighter extends FieldHighlighter {
  protected FieldByteOffsetStrategy fieldByteOffsetStrategy;

  public OcrFieldHighlighter(String field, FieldOffsetStrategy fieldOffsetStrategy,
                             FieldByteOffsetStrategy fieldByteOffsetStrategy, PassageScorer passageScorer,
                             BreakIterator breakIter, OcrPassageFormatter formatter, int maxPassages,
                             int maxNoHighlightPassages) {
    super(field, fieldOffsetStrategy, breakIter, passageScorer, maxPassages, maxNoHighlightPassages, formatter);
    this.fieldByteOffsetStrategy = fieldByteOffsetStrategy;
  }

  /**
   * The primary method -- highlight this doc, assuming a specific field and given this content.
   *
   * Largely copied from {@link FieldHighlighter#highlightFieldForDoc(LeafReader, int, String)}, modified to support
   * an {@link IterableCharSequence} as content.
   */
  public OcrSnippet[] highlightFieldForDoc(LeafReader reader, int docId, IterableCharSequence content) throws IOException {
    // note: it'd be nice to accept a CharSequence for content, but we need a CharacterIterator impl for it.
    if (content.length() == 0) {
      return null; // nothing to do
    }

    breakIterator.setText(content);

    Passage[] passages;
    if (content.getOffsetType() == OffsetType.BYTES && content.getCharset() == StandardCharsets.UTF_8) {
      try (ByteOffsetsEnum byteOffsetsEnums = fieldByteOffsetStrategy.getByteOffsetsEnum(reader, docId)) {
        passages = highlightByteOffsetsEnums(byteOffsetsEnums);
      }
    } else {
      try (OffsetsEnum offsetsEnums = fieldOffsetStrategy.getOffsetsEnum(reader, docId, null)) {
        passages = highlightOffsetsEnums(offsetsEnums);// and breakIterator & scorer
      }
    }

    // Format the resulting Passages.
    if (passages.length == 0) {
      // no passages were returned, so ask for a default summary
      passages = getSummaryPassagesNoHighlight(maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages);
    }

    if (passages.length > 0) {
      return ((OcrPassageFormatter) passageFormatter).format(passages, content);
    } else {
      return null;
    }
  }

  /**
   * Highlight passages from the document using the byte offsets in the payloads of the matching terms.
   *
   * Largely copied from {@link FieldHighlighter#highlightOffsetsEnums(OffsetsEnum)}, modified to load the byte offsets
   * from the term payloads.
   */
  protected Passage[] highlightByteOffsetsEnums(ByteOffsetsEnum off) throws IOException {
    final int contentLength = this.breakIterator.getText().getEndIndex();
    if (!off.nextPosition()) {
      return new Passage[0];
    }
    PriorityQueue<Passage> passageQueue = new PriorityQueue<>(Math.min(64, maxPassages + 1), (left, right) -> {
      if (left.getScore() < right.getScore()) {
        return -1;
      } else if (left.getScore() > right.getScore()) {
        return 1;
      } else {
        return left.getStartOffset() - right.getStartOffset();
      }
    });
    Passage passage = new Passage(); // the current passage in-progress.  Will either get reset or added to queue.
    do {
      int offset = off.byteOffset();
      this.breakIterator.getText().setIndex(offset);
      int end = offset;
      while (true) {
        char c = this.breakIterator.getText().next();
        end += Character.toString(c).getBytes(StandardCharsets.UTF_8).length;
        if (!Character.isLetter(c)) {
          break;
        }
      }
      if (offset < contentLength && end > contentLength) {
        continue;
      }
      // See if this term should be part of a new passage.
      if (offset >= passage.getEndOffset()) {
        passage = maybeAddPassage(passageQueue, passageScorer, passage, contentLength);
        // if we exceed limit, we are done
        if (offset >= contentLength) {
          break;
        }
        // advance breakIterator
        passage.setStartOffset(Math.max(this.breakIterator.preceding(offset + 1), 0));
        passage.setEndOffset(Math.min(this.breakIterator.following(offset), contentLength));
      }
      // Add this term to the passage.
      BytesRef term = off.getTerm();// a reference; safe to refer to
      assert term != null;
      passage.addMatch(offset, end, term, off.freq());
    } while (off.nextPosition());
    maybeAddPassage(passageQueue, passageScorer, passage, contentLength);

    Passage[] passages = passageQueue.toArray(new Passage[passageQueue.size()]);
    // sort in ascending order
    Arrays.sort(passages, Comparator.comparingInt(Passage::getStartOffset));
    return passages;
  }

  /** Completely copied from {@link FieldHighlighter} due to private access there. */
  private Passage maybeAddPassage(PriorityQueue<Passage> passageQueue, PassageScorer scorer, Passage passage,
                                  int contentLength) {
    if (passage.getStartOffset() == -1) {
      // empty passage, we can ignore it
      return passage;
    }
    passage.setScore(scorer.score(passage, contentLength));
    // new sentence: first add 'passage' to queue
    if (passageQueue.size() == maxPassages && passage.getScore() < passageQueue.peek().getScore()) {
      passage.reset(); // can't compete, just reset it
    } else {
      passageQueue.offer(passage);
      if (passageQueue.size() > maxPassages) {
        passage = passageQueue.poll();
        passage.reset();
      } else {
        passage = new Passage();
      }
    }
    return passage;
  }
}
