package io.github.dsh.sources;

import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;

/** 与上游 Intl.Segmenter('zh', word) 一样按 ICU 字典分词，不把中文逐字算作一个词。 */
public final class SourceText {
  private SourceText() {}

  public static int wordCount(String text) {
    BreakIterator words = BreakIterator.getWordInstance(ULocale.CHINESE);
    words.setText(text);
    int count = 0;
    for (int end = words.first(); (end = words.next()) != BreakIterator.DONE; )
      if (words.getRuleStatus() >= BreakIterator.WORD_NUMBER) count++;
    return count;
  }

  public static String checkedImText(String text) {
    int count = wordCount(text);
    if (count > 100)
      throw new IllegalArgumentException(
          "IM message has "
              + count
              + " words; maximum is 100. Rewrite as a short high-level update. Do not split it into"
              + " multiple messages.");
    return text;
  }
}
