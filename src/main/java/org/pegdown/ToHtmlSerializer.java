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

public class ToHtmlSerializer implements Visitor, HeaderIdComputer {
    protected Printer printer = new Printer(this);
    protected final Map<String, ReferenceNode> references = new HashMap<String, ReferenceNode>();
    protected final Map<String, String> abbreviations = new HashMap<String, String>();
    protected final LinkRenderer linkRenderer;
    protected final List<ToHtmlSerializerPlugin> plugins;

    protected TableNode currentTableNode;
    protected int currentTableColumn;
    protected boolean inTableHeader;
    protected int rootNodeRecursion = 0;
    protected boolean tocGenerationVisit = false;

    protected Map<String, VerbatimSerializer> verbatimSerializers;
    protected Map<String, Integer> referencedFootnotes = new HashMap<String, Integer>();
    protected Map<Integer, String> headerOffsetAnchorIds = new HashMap<Integer, String>();

    // vsch: override this to modify what attributes get output with every node
    // tocGenerationVisit is true if this output is for TOC
    public Attributes preview(Node node, String tag, Attributes attributes, boolean tocGenerationVisit) {
        return attributes;
    }

    // vsch: default behaviour unchanged
    // vsch: override to return the id you want to use for the header and the anchor link if there is one
    // vsch: if the value you return is an empty string then header will have no id, and if there was an anchor link it
    // vsch: will be removed from the output. Returning any other value will set the header id to it and also change the
    // vsch: name and target of the anchor link, if there is one
    // vsch: anchorLinkNode could be null
    // vsch: All calls are performed before the top most root node is processed and called in depth first order
    // vsch: that way the ids will not depend on whether TOC is used or where in the text it is located.

    // vsch: Caution: this will be called with a TaskListItemNode tag == 'li' and tag == 'p' for the same node that is a paragraph wrapped task list item
    // vsch: allowing you to add attributes to the p tag.
    public String computeHeaderId(HeaderNode node, AnchorLinkNode anchorLinkNode, String headerText) {
        return node.isToc() ? node.getId() : (anchorLinkNode != null ? anchorLinkNode.getName() : "");
    }

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

    protected void addAbbreviations(RootNode node) {
        for (AbbreviationNode abbrNode : node.getAbbreviations()) {
            visitChildren(abbrNode);
            String abbr = printer.getString();
            printer.clear();
            abbrNode.getExpansion().accept(this);
            String expansion = printer.getString();
            abbreviations.put(abbr, expansion);
            printer.clear();
        }
    }

    public void visit(RootNode node) {
        rootNodeRecursion++;
        try {
            if (rootNodeRecursion == 1) {
                // compute all the header node id's since these may be affected by the calling order
                HeaderIdComputingVisitor serializer = new HeaderIdComputingVisitor(this);
                serializer.visit(node);
                headerOffsetAnchorIds = serializer.headerOffsetAnchorIds;

                for (ReferenceNode refNode : node.getReferences()) {
                    visitChildren(refNode);
                    references.put(normalize(printer.getString()), refNode);
                    printer.clear();
                }

                addAbbreviations(node);
            }

            visitChildren(node);

            if (rootNodeRecursion == 1 && referencedFootnotes.size() > 0) {
                Map<Integer, FootnoteNode> footnotes = new HashMap<Integer, FootnoteNode>();

                for (FootnoteNode footnoteNode : node.getFootnotes()) {
                    footnotes.put(referencedFootnotes.get(footnoteNode.getLabel()), footnoteNode);
                }

                printFootnotes(footnotes);
            }
        } finally {
            rootNodeRecursion--;
        }
    }

    protected void printFootnotes(Map<Integer, FootnoteNode> footnotes) {
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
        String fnNum = String.valueOf(num);
        Attributes attributes = new Attributes();
        attributes.add("id", "fnref-" + fnNum);
        printer.print("<sup").print(preview(node, "sup", attributes, tocGenerationVisit)).print("><a href=\"#fn-").print(fnNum).print("\" class=\"footnote-ref\">").print(fnNum).print("</a></sup>");
    }

    public void visit(AbbreviationNode node) {
    }

    public void visit(AnchorLinkNode node) {
        printLink(node, linkRenderer.render(node));
    }

    public void visit(AutoLinkNode node) {
        printLink(node, linkRenderer.render(node));
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
        printImageTag(node, linkRenderer.render(node, text));
    }

    public void visit(ExpLinkNode node) {
        String text = printChildrenToString(node);
        printLink(node, linkRenderer.render(node, text));
    }

    public void visit(HeaderNode node) {
        boolean startWasNewLine = printer.endsWithNewLine();
        String tag = "h" + node.getLevel();
        Attributes attributes = new Attributes();
        TextNode insertFirstChild = null;
        int nSkipFirst = 0;
        AnchorLinkNode anchorLinkNode = null;

        if (node.getChildren().size() > 0 && node.getChildren().get(0) instanceof AnchorLinkNode) {
            anchorLinkNode = (AnchorLinkNode) node.getChildren().get(0);
        }

        assert headerOffsetAnchorIds.containsKey(node.getStartIndex());
        String anchorId = headerOffsetAnchorIds.get(node.getStartIndex());

        if (!anchorId.isEmpty() && (node.isToc() || anchorLinkNode != null && !anchorLinkNode.getName().equals(anchorId))) {
            attributes.add("id", anchorId);
        }

        // vsch: replace the first child if it is an anchor and its name isn't the computed id
        if (anchorLinkNode != null && !anchorLinkNode.getName().equals(anchorId)) {
            nSkipFirst = 1;
            if (!anchorId.isEmpty()) {
                insertFirstChild = new AnchorLinkNode(anchorId, anchorLinkNode.getText(), "");
                insertFirstChild.setStartIndex(anchorLinkNode.getStartIndex());
                insertFirstChild.setEndIndex(anchorLinkNode.getEndIndex());
            } else {
                insertFirstChild = new TextNode(anchorLinkNode.getText());
                insertFirstChild.setStartIndex(anchorLinkNode.getStartIndex());
                insertFirstChild.setEndIndex(anchorLinkNode.getEndIndex());
            }
        }

        printer.println();
        printer.print("<").print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print(">");
        if (insertFirstChild != null) insertFirstChild.accept(this);
        visitChildrenSkipFirst(node, nSkipFirst);
        printer.print('<').print('/').print(tag).print('>');

        if (startWasNewLine) printer.println();
    }

    public void visit(HtmlBlockNode node) {
        String text = node.getText();
        if (text.length() > 0) {
            printer.println();
            printer.print(text);
        }
    }

    public void visit(InlineHtmlNode node) {
        printer.print(node.getText());
    }

    // vsch: override this to generate different task list items
    public void printTaskListItemMarker(Printer printer, TaskListNode node, boolean isParaWrapped) {
        printer.print("<input type=\"checkbox\" class=\"task-list-item-checkbox\"" + (node.isDone() ? " checked=\"checked\"" : "") + " disabled=\"disabled\"></input>");
    }

    public void visit(ListItemNode node) {
        if (node instanceof TaskListNode) {
            // vsch: #185 handle GitHub style task list items, these are a bit messy because the <input> checkbox needs to be
            // included inside the optional <p></p> first grand-child of the list item, first child is always RootNode
            // because the list item text is recursively parsed.
            List<Node> children = node.getChildren().get(0).getChildren();
            Node firstChild = children.size() > 0 ? children.get(0) : null;
            boolean firstIsPara = firstChild instanceof ParaNode;
            int indent = node.getChildren().size() > 1 ? 2 : 0;
            boolean startWasNewLine = printer.endsWithNewLine();
            Attributes attributes = new Attributes().add("class", "task-list-item");

            printer.println().print("<li").print(preview(node, "li", attributes, tocGenerationVisit)).print(">").indent(indent);
            if (firstIsPara) {
                Attributes paraAttributes = new Attributes();
                printer.println().print("<p").print(preview(node, "p", paraAttributes, tocGenerationVisit)).print(">");
                printTaskListItemMarker(printer, (TaskListNode) node, true);
                visitChildren((SuperNode) firstChild);

                // render the other children, the p tag is taken care of here
                visitChildrenSkipFirst(node);
                printer.print("</p>");
            } else {
                printTaskListItemMarker(printer, (TaskListNode) node, false);
                visitChildren(node);
            }
            printer.indent(-indent).printchkln(indent != 0).print("</li>")
                    .printchkln(startWasNewLine);
        } else {
            printConditionallyIndentedTag(node, "li");
        }
    }

    public void visit(MailLinkNode node) {
        printLink(node, linkRenderer.render(node));
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
        // vsch: here we can have a ReferenceNode.DUMMY_REFERENCE_KEY which will have no children
        String key = node.referenceKey != null && node.referenceKey.getChildren().size() != 0 ? printChildrenToString(node.referenceKey) : text;
        ReferenceNode refNode = references.get(normalize(key));
        if (refNode == null) { // "fake" reference image link
            printer.print("![").print(text).print(']');
            if (node.separatorSpace != null) {
                printer.print(node.separatorSpace).print('[');
                if (node.referenceKey != null && node.referenceKey.getChildren().size() != 0) printer.print(key);
                printer.print(']');
            }
        } else printImageTag(node, linkRenderer.render(node, refNode.getUrl(), refNode.getTitle(), text));
    }

    public void visit(RefLinkNode node) {
        String text = printChildrenToString(node);
        // vsch: here we can have a ReferenceNode.DUMMY_REFERENCE_KEY which will have no children
        String key = node.referenceKey != null && node.referenceKey.getChildren().size() != 0 ? printChildrenToString(node.referenceKey) : text;
        ReferenceNode refNode = references.get(normalize(key));
        if (refNode == null) { // "fake" reference link
            printer.print('[').print(text).print(']');
            if (node.separatorSpace != null) {
                printer.print(node.separatorSpace).print('[');
                if (node.referenceKey != null && node.referenceKey.getChildren().size() != 0) printer.print(key);
                printer.print(']');
            }
        } else printLink(node, linkRenderer.render(node, refNode.getUrl(), refNode.getTitle(), text));
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
        Attributes attributes = new Attributes();
        printer.println().print('<').print("caption").print(preview(node, "caption", attributes, tocGenerationVisit)).print('>');
        visitChildren(node);
        printer.print("</caption>");
    }

    public void visit(TableCellNode node) {
        String tag = inTableHeader ? "th" : "td";
        List<TableColumnNode> columns = currentTableNode.getColumns();
        TableColumnNode column = columns.get(Math.min(currentTableColumn, columns.size() - 1));

        Attributes attributes = new Attributes();
        getColumnAttributes(column, attributes);
        if (node.getColSpan() > 1) attributes.add("colspan", String.valueOf(node.getColSpan()));

        printer.print('<').print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print('>');
        visitChildren(node);
        printer.print('<').print('/').print(tag).print('>');

        currentTableColumn += node.getColSpan();
    }

    public void getColumnAttributes(TableColumnNode node, Attributes attributes) {
        switch (node.getAlignment()) {
            case None:
                break;
            case Left:
                attributes.add("align", "left");
                break;
            case Right:
                attributes.add("align", "right");
                break;
            case Center:
                attributes.add("align", "center");
                break;
            default:
                throw new IllegalStateException();
        }
    }

    public void visit(TableColumnNode node) {
        Attributes attributes = new Attributes();
        getColumnAttributes(node, attributes);
        printer.print(attributes);
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

    public void visit(TocNode node) {
        if (!node.getHeaders().isEmpty()) {
            int initLevel = node.getHeaders().get(0).getLevel();
            int lastLevel = node.getHeaders().get(0).getLevel();

            Attributes attributes = new Attributes();
            AnchorLinkNode anchorLinkNode;
            String headerId;

            printer.print('<').print("ul").print(preview(node, "ul", attributes, tocGenerationVisit)).print('>');

            for (int i = 0; i < node.getHeaders().size(); ++i) {

                HeaderNode header = node.getHeaders().get(i);

                // ignore the level less than toc limit
                if (header.getLevel() > node.getLevel()) {
                    continue;
                }

                if (lastLevel < header.getLevel()) {
                    for (int lv = lastLevel; lv < header.getLevel(); ++lv) {
                        printer.print("<ul>").println();
                    }
                } else if (lastLevel == header.getLevel()) {
                    if (i != 0) {
                        printer.print("</li>").println();
                    }
                } else {
                    printer.print("</li>").println();
                    for (int lv = header.getLevel(); lv < lastLevel; ++lv) {
                        printer.print("</ul></li>").println();
                    }
                }

                if (header.getChildren().size() > 0 && header.getChildren().get(0) instanceof AnchorLinkNode) {
                    anchorLinkNode = (AnchorLinkNode) header.getChildren().get(0);
                } else {
                    anchorLinkNode = null;
                }

                assert headerOffsetAnchorIds.containsKey(header.getStartIndex());
                headerId = headerOffsetAnchorIds.get(header.getStartIndex());

                printer.print("<li><a href=\"#").print(headerId).print("\">");
                if (anchorLinkNode != null && header.getChildren().size() == 1) {
                    // must be extanchors with wrap, all text is in the anchor
                    printer.print(anchorLinkNode.getText());
                } else {
                    tocGenerationVisit = true;
                    visitChildrenSkipFirst(header, anchorLinkNode != null ? 1 : 0);
                    tocGenerationVisit = false;
                }
                printer.print("</a>");

                lastLevel = header.getLevel();
            }
            for (int i = initLevel - 1; i < lastLevel; ++i) {
                printer.print("</li></ul>").println();
            }

            printer.println();
        }
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
        printLink(node, linkRenderer.render(node));
    }

    public void visit(TextNode node) {
        if (!abbreviations.isEmpty()) {
            printWithAbbreviations(node.getText());
        } else {
            printer.print(node.getText());
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
        visitChildrenSkipFirst(node, 0);
    }

    // helpers
    protected void visitChildrenSkipFirst(SuperNode node) {
        visitChildrenSkipFirst(node, 1);
    }

    // helpers
    protected void visitChildrenSkipFirst(SuperNode node, int nToSkip) {
        for (Node child : node.getChildren()) {
            if (nToSkip > 0) nToSkip--;
            else child.accept(this);
        }
    }

    protected void printTag(TextNode node, String tag) {
        Attributes attributes = new Attributes();
        printer.print('<').print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print('>');
        printer.printEncoded(node.getText());
        printer.print('<').print('/').print(tag).print('>');
    }

    protected void printTag(SuperNode node, String tag) {
        Attributes attributes = new Attributes();
        printer.print('<').print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print('>');
        visitChildren(node);
        printer.print('<').print('/').print(tag).print('>');
    }

    protected void printBreakBeforeTag(SuperNode node, String tag) {
        boolean startWasNewLine = printer.endsWithNewLine();
        printer.println();
        printTag(node, tag);
        if (startWasNewLine) printer.println();
    }

    protected void printBreakBeforeTagWithId(SuperNode node, String tag, String id) {
        boolean startWasNewLine = printer.endsWithNewLine();
        Attributes attributes = new Attributes().add("id", id);

        printer.println();
        printer.print("<").print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print(">");
        visitChildren(node);
        printer.print('<').print('/').print(tag).print('>');

        if (startWasNewLine) printer.println();
    }

    protected void printIndentedTag(SuperNode node, String tag) {
        Attributes attributes = new Attributes();
        printer.println().print('<').print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print('>').indent(+2);
        visitChildren(node);
        printer.indent(-2).println().print('<').print('/').print(tag).print('>');
    }

    protected void printConditionallyIndentedTag(SuperNode node, String tag) {
        Attributes attributes = new Attributes();
        if (node.getChildren().size() > 1) {
            printer.println().print('<').print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print('>').indent(+2);
            visitChildren(node);
            printer.indent(-2).println().print('<').print('/').print(tag).print('>');
        } else {
            boolean startWasNewLine = printer.endsWithNewLine();

            printer.println().print('<').print(tag).print(preview(node, tag, attributes, tocGenerationVisit)).print('>');
            visitChildren(node);
            printer.print('<').print('/').print(tag).print('>').printchkln(startWasNewLine);
        }
    }

    protected void printImageTag(Node node, LinkRenderer.Rendering rendering) {
        Attributes attributes = new Attributes().add("src", rendering.href);

        // shouldn't include the alt attribute if its empty
        if (!rendering.text.equals("")) {
            attributes.add("alt", rendering.text);
        }

        attributes.addAll(rendering.attributes);
        printer.print("<img").print(preview(node, "img", attributes, tocGenerationVisit)).print(" />");
    }

    protected void printLink(Node node, LinkRenderer.Rendering rendering) {
        Attributes attributes = new Attributes().add("href", rendering.href).addAll(rendering.attributes);
        printer.print('<').print('a').print(preview(node, "a", attributes, tocGenerationVisit)).print('>').print(rendering.text).print("</a>");
    }

    protected void printAttribute(String name, String value) {
        // vsch: escape " and \ in attribute value strings
        printer.print(' ').print(name).print('=').print('"').print(value.replace("\\", "\\\\").replace("\"", "\\\"")).print('"');
    }

    protected String printChildrenToString(SuperNode node) {
        Printer priorPrinter = printer;
        printer = new Printer(this);
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
        Map<Integer, Map.Entry<String, String>> expansions = new TreeMap<Integer, Map.Entry<String, String>>();

        findAbbreviations(string, expansions);

        if (!expansions.isEmpty()) {
            int ix = 0;
            for (Map.Entry<Integer, Map.Entry<String, String>> entry : expansions.entrySet()) {
                int sx = entry.getKey();
                String abbr = entry.getValue().getKey();
                String expansion = entry.getValue().getValue();

                // print the text before the abbreviation
                if (sx > ix) printer.printEncoded(string.substring(ix, sx));

                printAbbrExpansion(abbr, expansion);
                ix = sx + abbr.length();
            }
            printer.print(string.substring(ix));
        } else {
            printer.print(string);
        }
    }

    protected void findAbbreviations(String string, Map<Integer, Map.Entry<String, String>> expansions) {
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
                expansions.put(sx, entry);
            }
        }
    }

    protected void printAbbrExpansion(String abbr, String expansion) {
        printer.print("<abbr");
        if (StringUtils.isNotEmpty(expansion)) {
            printer.print(" title=\"");
            printer.printEncoded(expansion);
            printer.print('"');
        }
        printer.print('>');
        printer.printEncoded(abbr);
        printer.print("</abbr>");
    }
}
