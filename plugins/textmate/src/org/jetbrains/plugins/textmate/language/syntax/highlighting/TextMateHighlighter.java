package org.jetbrains.plugins.textmate.language.syntax.highlighting;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.openapi.util.text.Strings;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.language.TextMateScopeComparator;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class TextMateHighlighter extends SyntaxHighlighterBase {
  private static final PlainSyntaxHighlighter PLAIN_SYNTAX_HIGHLIGHTER = new PlainSyntaxHighlighter();

  @Nullable
  private final Lexer myLexer;

  public TextMateHighlighter(@Nullable Lexer lexer) {
    myLexer = lexer;
  }

  @NotNull
  @Override
  public Lexer getHighlightingLexer() {
    return myLexer == null ? PLAIN_SYNTAX_HIGHLIGHTER.getHighlightingLexer() : myLexer;
  }

  @Override
  public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
    if (!(tokenType instanceof TextMateElementType)) return PLAIN_SYNTAX_HIGHLIGHTER.getTokenHighlights(tokenType);
    TextMateService service = TextMateService.getInstance();
    Map<CharSequence, TextMateTextAttributesAdapter> customHighlightingColors = service.getCustomHighlightingColors();

    Set<CharSequence> highlightingRules = ContainerUtil.union(customHighlightingColors.keySet(), TextMateTheme.INSTANCE.getRules());

    TextMateScope textMateScope = trimEmbeddedScope((TextMateElementType)tokenType);
    List<CharSequence> selectors = ContainerUtil.reverse(new TextMateScopeComparator<>(textMateScope, Function.identity())
                                                           .sortAndFilter(highlightingRules));
    return ContainerUtil.map2Array(selectors, TextAttributesKey.class, rule -> {
      TextMateTextAttributesAdapter customTextAttributes = customHighlightingColors.get(rule);
      return customTextAttributes != null ? customTextAttributes.getTextAttributesKey(TextMateTheme.INSTANCE)
                                          : TextMateTheme.INSTANCE.getTextAttributesKey(rule);
    });
  }

  private static TextMateScope trimEmbeddedScope(TextMateElementType tokenType) {
    TextMateScope result = TextMateScope.EMPTY;
    TextMateScope current = tokenType.getScope();
    while (current != null) {
      CharSequence scopeName = current.getScopeName();
      result = result.add(scopeName);
      if (scopeName != null && Strings.contains(scopeName, ".embedded.")) {
        break;
      }
      current = current.getParent();
    }
    return result;
  }
}
