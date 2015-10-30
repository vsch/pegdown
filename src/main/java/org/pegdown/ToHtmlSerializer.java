/*
 * Copyright (C) 2010-2011 Mathias Doenitz
 *
 * Based on peg-markdown (C) 2008-2010 John MacFarlane
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pegdown;

import org.parboiled.common.StringUtils;
import org.pegdown.ast.*;
import org.pegdown.plugins.ToHtmlSerializerPlugin;

import java.util.*;

import static org.parboiled.common.Preconditions.checkArgNotNull;

public class ToHtmlSerializer implements Visitor {

    protected Printer printer = new Printer();
    protected final Map<String, ReferenceNode> references = new HashMap<String, ReferenceNode>();
    protected final Map<String, String> abbreviations = new HashMap<String, String>();
    protected final LinkRenderer linkRenderer;
    protected final List<ToHtmlSerializerPlugin> plugins;

    protected TableNode currentTableNode;
    protected int currentTableColumn;
    protected boolean inTableHeader;
    protected int rootNodeRecursion = 0;

    protected Map<String, VerbatimSerializer> verbatimSerializers;
    protected Map<String, Integer> referencedFootnotes = new HashMap<String, Integer>();

    public ToHtmlSerializer(LinkRenderer linkRenderer) {
        this(linkRenderer, Collections.<ToHtmlSerializerPlugin>emptyList());
    }

    public ToHtmlSerializer(LinkRenderer linkRenderer, List<ToHtmlSerializerPlugin> plugins) {
        this(linkRenderer, Collections.<String, VerbatimSerializer>emptyMap(), plugins);
    }

    public ToHtmlSerializer(final LinkRenderer linkRenderer, final Map<String, VerbatimSerializer> verbatimSerializers) {
        this(linkRenderer, verbatimSerializers, Collections.<ToHtmlSerializerPlugin>emptyList());
    }

    public ToHtmlSerializer(final LinkRenderer linkRenderer, final Map<String, VerbatimSerializer> verbatimSerializers, final List<ToHtmlSerializerPlugin> plugins) {
        this.linkRenderer = linkRenderer;
        this.verbatimSerializers = new HashMap<String, VerbatimSerializer>(verbatimSerializers);
        if (!this.verbatimSerializers.containsKey(VerbatimSerializer.DEFAULT)) {
            this.verbatimSerializers.put(VerbatimSerializer.DEFAULT, DefaultVerbatimSerializer.INSTANCE);
        }
        this.plugins = plugins;
    }

    public String toHtml(RootNode astRoot) {
        checkArgNotNull(astRoot, "astRoot");
        astRoot.accept(this);
        return printer.getString();
    }

    public void visit(RootNode node) {
        rootNodeRecursion++;
        try {
            for (ReferenceNode refNode : node.getReferences()) {
                visitChildren(refNode);
                references.put(normalize(printer.getString()), refNode);
                printer.clear();
            }

            for (AbbreviationNode abbrNode : node.getAbbreviations()) {
                visitChildren(abbrNode);
                String abbr = printer.getString();
                printer.clear();
                abbrNode.getExpansion().accept(this);
                String expansion = printer.getString();
                abbreviations.put(abbr, expansion);
                printer.clear();
            }

            visitChildren(node);

            if (rootNodeRecursion == 1 && referencedFootnotes.size() > 0) {
                Map<Integer, FootnoteNode> footnotes = new HashMap<Integer, FootnoteNode>();

                for (FootnoteNode footnoteNode : node.getFootnotes()) {
                    footnotes.put(referencedFootnotes.get(footnoteNode.getLabel()), footnoteNode);
                }

                printer.print("<div class=\"footnotes\">\n");
                printer.print("<hr/>\n");
                printer.print("<ol>\n");

                for (int i = 0; i < referencedFootnotes.size(); i++) {
                    int num = i + 1;
                    if (!footnotes.containsKey(num)) {
                        // empty footnote
                        printer.print("<li id=\"fn-" + num + "\"><p><a href=\"#fnref-" + num + "\" class=\"footnote-backref\">&#8617;</a></p></li>\n");
                    } else {
                        printer.print("<li id=\"fn-" + num + "\"><p>");
                        visitChildren((SuperNode) footnotes.get(num).getFootnote());
                        printer.print("<a href=\"#fnref-" + num + "\" class=\"footnote-backref\">&#8617;</a></p>");
                        printer.print("</li>\n");
                    }
                }

                printer.print("</ol>\n");
                printer.print("</div>\n");
            }
        } finally {
            rootNodeRecursion--;
        }
    }

    public void visit(FootnoteNode node) {
        // this one we don't output for HTML, it is done at the bottom of the page
    }

    public void visit(FootnoteRefNode node) {
        String footnote = node.getLabel();
        int num = referencedFootnotes.size() + 1;

        if (!referencedFootnotes.containsKey(footnote)) {
            referencedFootnotes.put(footnote, num);
        } else {
            num = referencedFootnotes.get(footnote);
        }
        printer.print("<sup id=\"fnref-" + num + "\"><a href=\"#fn-" + num + "\" class=\"footnote-ref\">" + num + "</a></sup>");
    }

    public void visit(AbbreviationNode node) {
    }

    public void visit(AnchorLinkNode node) {
        printLink(linkRenderer.render(node));
    }

    public void visit(AutoLinkNode node) {
        printLink(linkRenderer.render(node));
    }

    public void visit(BlockQuoteNode node) {
        printIndentedTag(node, "blockquote");
    }

    public void visit(BulletListNode node) {
        printIndentedTag(node, "ul");
    }

    public void visit(CodeNode node) {
        printTag(node, "code");
    }

    public void visit(DefinitionListNode node) {
        printIndentedTag(node, "dl");
    }

    public void visit(DefinitionNode node) {
        printConditionallyIndentedTag(node, "dd");
    }

    public void visit(DefinitionTermNode node) {
        printConditionallyIndentedTag(node, "dt");
    }

    public void visit(ExpImageNode node) {
        String text = printChildrenToString(node);
        printImageTag(linkRenderer.render(node, text));
    }

    public void visit(ExpLinkNode node) {
        String text = printChildrenToString(node);
        printLink(linkRenderer.render(node, text));
    }

    public void visit(HeaderNode node) {
        printBreakBeforeTag(node, "h" + node.getLevel());
    }

    public void visit(HtmlBlockNode node) {
        String text = node.getText();
        if (text.length() > 0) printer.println();
        printer.print(text);
    }

    public void visit(InlineHtmlNode node) {
        printer.print(node.getText());
    }

    public void visit(ListItemNode node) {
        if (node instanceof TaskListNode) {
            // vsch: #185 handle GitHub style task list items, these are a bit messy because the <input> checkbox needs to be
            // included inside the optional <p></p> first grand-child of the list item, first child is always RootNode
            // because the list item text is recursively parsed.
            Node firstChild = node.getChildren().get(0).getChildren().get(0);
            boolean firstIsPara = firstChild instanceof ParaNode;
            int indent = node.getChildren().size() > 1 ? 2 : 0;
            boolean startWasNewLine = printer.endsWithNewLine();

            printer.println().print("<li class=\"task-list-item\">").indent(indent);
            if (firstIsPara) {
                printer.println().print("<p>");
                printer.print("<input type=\"checkbox\" class=\"task-list-item-checkbox\"" + (((TaskListNode) node).isDone() ? " checked=\"checked\"" : "") + " disabled=\"disabled\"></input>");
                visitChildren((SuperNode) firstChild);

                // render the other children, the p tag is taken care of here
                visitChildrenSkipFirst(node);
                printer.print("</p>");
            } else {
                printer.print("<input type=\"checkbox\" class=\"task-list-item-checkbox\"" + (((TaskListNode) node).isDone() ? " checked=\"checked\"" : "") + " disabled=\"disabled\"></input>");
                visitChildren(node);
            }
            printer.indent(-indent).printchkln(indent != 0).print("</li>")
                    .printchkln(startWasNewLine);
        } else {
            printConditionallyIndentedTag(node, "li");
        }
    }

    public void visit(MailLinkNode node) {
        printLink(linkRenderer.render(node));
    }

    public void visit(OrderedListNode node) {
        printIndentedTag(node, "ol");
    }

    public void visit(ParaNode node) {
        printBreakBeforeTag(node, "p");
    }

    public void visit(QuotedNode node) {
        switch (node.getType()) {
            case DoubleAngle:
                printer.print("&laquo;");
                visitChildren(node);
                printer.print("&raquo;");
                break;
            case Double:
                printer.print("&ldquo;");
                visitChildren(node);
                printer.print("&rdquo;");
                break;
            case Single:
                printer.print("&lsquo;");
                visitChildren(node);
                printer.print("&rsquo;");
                break;
        }
    }

    public void visit(ReferenceNode node) {
        // reference nodes are not printed
    }

    public void visit(RefImageNode node) {
        String text = printChildrenToString(node);
        String key = node.referenceKey != null ? printChildrenToString(node.referenceKey) : text;
        ReferenceNode refNode = references.get(normalize(key));
        if (refNode == null) { // "fake" reference image link
            printer.print("![").print(text).print(']');
            if (node.separatorSpace != null) {
                printer.print(node.separatorSpace).print('[');
                if (node.referenceKey != null) printer.print(key);
                printer.print(']');
            }
        } else printImageTag(linkRenderer.render(node, refNode.getUrl(), refNode.getTitle(), text));
    }

    public void visit(RefLinkNode node) {
        String text = printChildrenToString(node);
        String key = node.referenceKey != null ? printChildrenToString(node.referenceKey) : text;
        ReferenceNode refNode = references.get(normalize(key));
        if (refNode == null) { // "fake" reference link
            printer.print('[').print(text).print(']');
            if (node.separatorSpace != null) {
                printer.print(node.separatorSpace).print('[');
                if (node.referenceKey != null) printer.print(key);
                printer.print(']');
            }
        } else printLink(linkRenderer.render(node, refNode.getUrl(), refNode.getTitle(), text));
    }

    public void visit(SimpleNode node) {
        switch (node.getType()) {
            case Apostrophe:
                printer.print("&rsquo;");
                break;
            case Ellipsis:
                printer.print("&hellip;");
                break;
            case Emdash:
                printer.print("&mdash;");
                break;
            case Endash:
                printer.print("&ndash;");
                break;
            case HRule:
                printer.println().print("<hr/>");
                break;
            case Linebreak:
                printer.print("<br/>");
                break;
            case Nbsp:
                printer.print("&nbsp;");
                break;
            default:
                throw new IllegalStateException();
        }
    }

    public void visit(StrongEmphSuperNode node) {
        if (node.isClosed()) {
            if (node.isStrong())
                printTag(node, "strong");
            else
                printTag(node, "em");
        } else {
            //sequence was not closed, treat open chars as ordinary chars
            printer.print(node.getChars());
            visitChildren(node);
        }
    }

    public void visit(StrikeNode node) {
        printTag(node, "del");
    }

    public void visit(TableBodyNode node) {
        printIndentedTag(node, "tbody");
    }

    @Override
    public void visit(TableCaptionNode node) {
        printer.println().print("<caption>");
        visitChildren(node);
        printer.print("</caption>");
    }

    public void visit(TableCellNode node) {
        String tag = inTableHeader ? "th" : "td";
        List<TableColumnNode> columns = currentTableNode.getColumns();
        TableColumnNode column = columns.get(Math.min(currentTableColumn, columns.size() - 1));

        printer.println().print('<').print(tag);
        column.accept(this);
        if (node.getColSpan() > 1) printer.print(" colspan=\"").print(Integer.toString(node.getColSpan())).print('"');
        printer.print('>');
        visitChildren(node);
        printer.print('<').print('/').print(tag).print('>');

        currentTableColumn += node.getColSpan();
    }

    public void visit(TableColumnNode node) {
        switch (node.getAlignment()) {
            case None:
                break;
            case Left:
                printer.print(" align=\"left\"");
                break;
            case Right:
                printer.print(" align=\"right\"");
                break;
            case Center:
                printer.print(" align=\"center\"");
                break;
            default:
                throw new IllegalStateException();
        }
    }

    public void visit(TableHeaderNode node) {
        inTableHeader = true;
        printIndentedTag(node, "thead");
        inTableHeader = false;
    }

    public void visit(TableNode node) {
        currentTableNode = node;
        printIndentedTag(node, "table");
        currentTableNode = null;
    }

    public void visit(TableRowNode node) {
        currentTableColumn = 0;
        printIndentedTag(node, "tr");
    }

    public void visit(VerbatimNode node) {
        VerbatimSerializer serializer = lookupSerializer(node.getType());
        serializer.serialize(node, printer);
    }

    protected VerbatimSerializer lookupSerializer(final String type) {
        if (type != null && verbatimSerializers.containsKey(type)) {
            return verbatimSerializers.get(type);
        } else {
            return verbatimSerializers.get(VerbatimSerializer.DEFAULT);
        }
    }

    public void visit(WikiLinkNode node) {
        printLink(linkRenderer.render(node));
    }

    public void visit(TextNode node) {
        if (abbreviations.isEmpty()) {
            printer.print(node.getText());
        } else {
            printWithAbbreviations(node.getText());
        }
    }

    public void visit(SpecialTextNode node) {
        printer.printEncoded(node.getText());
    }

    public void visit(SuperNode node) {
        visitChildren(node);
    }

    public void visit(Node node) {
        for (ToHtmlSerializerPlugin plugin : plugins) {
            if (plugin.visit(node, this, printer)) {
                return;
            }
        }
        // override this method for processing custom Node implementations
        throw new RuntimeException("Don't know how to handle node " + node);
    }

    // helpers
    protected void visitChildren(SuperNode node) {
        for (Node child : node.getChildren()) {
            child.accept(this);
        }
    }

    // helpers
    protected void visitChildrenSkipFirst(SuperNode node) {
        boolean first = true;
        for (Node child : node.getChildren()) {
            if (!first) child.accept(this);
            first = false;
        }
    }

    protected void printTag(TextNode node, String tag) {
        printer.print('<').print(tag).print('>');
        printer.printEncoded(node.getText());
        printer.print('<').print('/').print(tag).print('>');
    }

    protected void printTag(SuperNode node, String tag) {
        printer.print('<').print(tag).print('>');
        visitChildren(node);
        printer.print('<').print('/').print(tag).print('>');
    }

    protected void printBreakBeforeTag(SuperNode node, String tag) {
        boolean startWasNewLine = printer.endsWithNewLine();
        printer.println();
        printTag(node, tag);
        if (startWasNewLine) printer.println();
    }

    protected void printIndentedTag(SuperNode node, String tag) {
        printer.println().print('<').print(tag).print('>').indent(+2);
        visitChildren(node);
        printer.indent(-2).println().print('<').print('/').print(tag).print('>');
    }

    protected void printConditionallyIndentedTag(SuperNode node, String tag) {
        if (node.getChildren().size() > 1) {
            printer.println().print('<').print(tag).print('>').indent(+2);
            visitChildren(node);
            printer.indent(-2).println().print('<').print('/').print(tag).print('>');
        } else {
            boolean startWasNewLine = printer.endsWithNewLine();

            printer.println().print('<').print(tag).print('>');
            visitChildren(node);
            printer.print('<').print('/').print(tag).print('>').printchkln(startWasNewLine);
        }
    }

    protected void printImageTag(LinkRenderer.Rendering rendering) {
        printer.print("<img");
        printAttribute("src", rendering.href);
        // shouldn't include the alt attribute if its empty
        if (!rendering.text.equals("")) {
            printAttribute("alt", rendering.text);
        }
        for (LinkRenderer.Attribute attr : rendering.attributes) {
            printAttribute(attr.name, attr.value);
        }
        printer.print(" />");
    }

    protected void printLink(LinkRenderer.Rendering rendering) {
        printer.print('<').print('a');
        printAttribute("href", rendering.href);
        for (LinkRenderer.Attribute attr : rendering.attributes) {
            printAttribute(attr.name, attr.value);
        }
        printer.print('>').print(rendering.text).print("</a>");
    }

    protected void printAttribute(String name, String value) {
        printer.print(' ').print(name).print('=').print('"').print(value).print('"');
    }

    protected String printChildrenToString(SuperNode node) {
        Printer priorPrinter = printer;
        printer = new Printer();
        visitChildren(node);
        String result = printer.getString();
        printer = priorPrinter;
        return result;
    }

    protected String normalize(String string) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            switch (c) {
                case ' ':
                case '\n':
                case '\t':
                    continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    protected void printWithAbbreviations(String string) {
        Map<Integer, Map.Entry<String, String>> expansions = null;

        for (Map.Entry<String, String> entry : abbreviations.entrySet()) {
            // first check, whether we have a legal match
            String abbr = entry.getKey();

            int ix = 0;
            while (true) {
                int sx = string.indexOf(abbr, ix);
                if (sx == -1) break;

                // only allow whole word matches
                ix = sx + abbr.length();

                if (sx > 0 && Character.isLetterOrDigit(string.charAt(sx - 1))) continue;
                if (ix < string.length() && Character.isLetterOrDigit(string.charAt(ix))) {
                    continue;
                }

                // ok, legal match so save an expansions "task" for all matches
                if (expansions == null) {
                    expansions = new TreeMap<Integer, Map.Entry<String, String>>();
                }
                expansions.put(sx, entry);
            }
        }

        if (expansions != null) {
            int ix = 0;
            for (Map.Entry<Integer, Map.Entry<String, String>> entry : expansions.entrySet()) {
                int sx = entry.getKey();
                String abbr = entry.getValue().getKey();
                String expansion = entry.getValue().getValue();

                printer.printEncoded(string.substring(ix, sx));
                printer.print("<abbr");
                if (StringUtils.isNotEmpty(expansion)) {
                    printer.print(" title=\"");
                    printer.printEncoded(expansion);
                    printer.print('"');
                }
                printer.print('>');
                printer.printEncoded(abbr);
                printer.print("</abbr>");
                ix = sx + abbr.length();
            }
            printer.print(string.substring(ix));
        } else {
            printer.print(string);
        }
    }
}
