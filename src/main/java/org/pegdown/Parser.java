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

import org.parboiled.BaseParser;
import org.parboiled.Context;
import org.parboiled.Rule;
import org.parboiled.annotations.Cached;
import org.parboiled.annotations.DontSkipActionsInPredicates;
import org.parboiled.annotations.MemoMismatches;
import org.parboiled.common.ArrayBuilder;
import org.parboiled.common.ImmutableList;
import org.parboiled.parserunners.ParseRunner;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.ParsingResult;
import org.parboiled.support.StringBuilderVar;
import org.parboiled.support.StringVar;
import org.parboiled.support.Var;
import org.pegdown.ast.*;
import org.pegdown.ast.SimpleNode.Type;
import org.pegdown.plugins.PegDownPlugins;

import java.util.*;

import static org.parboiled.common.StringUtils.repeat;
import static org.parboiled.errors.ErrorUtils.printParseErrors;

/**
 * Parboiled parser for the standard and extended markdown syntax.
 * Builds an Abstract Syntax Tree (AST) of {@link Node} objects.
 */
@SuppressWarnings({ "InfiniteRecursion" })
public class Parser extends BaseParser<Object> implements Extensions {

    protected static final char CROSSED_OUT = '\uffff';

    public interface ParseRunnerProvider {
        ParseRunner<Node> get(Rule rule);
    }

    public static ParseRunnerProvider DefaultParseRunnerProvider =
            new Parser.ParseRunnerProvider() {
                public ParseRunner<Node> get(Rule rule) {
                    return new ReportingParseRunner<Node>(rule);
                }
            };

    protected final int options;
    protected final long maxParsingTimeInMillis;
    protected final ParseRunnerProvider parseRunnerProvider;
    protected final PegDownPlugins plugins;
    final List<AbbreviationNode> abbreviations = new ArrayList<AbbreviationNode>();
    final List<FootnoteNode> footnotes = new ArrayList<FootnoteNode>();
    final List<ReferenceNode> references = new ArrayList<ReferenceNode>();
    final List<HeaderNode> headers = new ArrayList<HeaderNode>();
    long parsingStartTimeStamp = 0L;

    public boolean debugMsg(String msg, String text) {
        System.out.println(msg + ": '" + text + "'");
        return true;
    }

    public Parser(Integer options, Long maxParsingTimeInMillis, ParseRunnerProvider parseRunnerProvider, PegDownPlugins plugins) {
        this.options = options;
        this.maxParsingTimeInMillis = maxParsingTimeInMillis;
        this.parseRunnerProvider = parseRunnerProvider;
        this.plugins = plugins;
    }

    public Parser(Integer options, Long maxParsingTimeInMillis, ParseRunnerProvider parseRunnerProvider) {
        this(options, maxParsingTimeInMillis, parseRunnerProvider, PegDownPlugins.NONE);
    }

    public RootNode parse(char[] source) {
        try {
            RootNode root = parseInternal(source);
            root.setAbbreviations(ImmutableList.copyOf(abbreviations));
            root.setReferences(ImmutableList.copyOf(references));
            root.setFootnotes(ImmutableList.copyOf(footnotes));
            return root;
        } finally {
            abbreviations.clear();
            references.clear();
            footnotes.clear();
        }
    }

    //************* BLOCKS ****************

    public Rule Root() {
        return NodeSequence(
                push(new RootNode()),
                ZeroOrMore(Block(), addAsChild())
        );
    }

    public Rule Block() {
        return Sequence(
                ZeroOrMore(BlankLine()),
                FirstOf(new ArrayBuilder<Rule>()
                        .add(plugins.getBlockPluginRules())
                        .add(BlockQuote(), Verbatim())
                        .addNonNulls(ext(FOOTNOTES) ? Footnote() : null)
                        .addNonNulls(ext(ABBREVIATIONS) ? Abbreviation() : null)
                        .add(Reference(), HorizontalRule(), Heading(), OrderedList(), BulletList(), HtmlBlock())
                        .addNonNulls(ext(TABLES) ? Table() : null)
                        .addNonNulls(ext(DEFINITIONS) ? DefinitionList() : null)
                        .addNonNulls(ext(FENCED_CODE_BLOCKS) ? FencedCodeBlock() : null)
                        .addNonNulls(ext(TOC) ? Toc() : null)
                        .add(Para(), Inlines())
                        .get()
                )
        );
    }

    public Rule Para() {
        return Sequence(
                NonindentSpace(),
                NodeSequence(
                        // The Para Rule only tests for the presence of a following blank line, but does not consume it.
                        // this means that phantom \n's will not be part of the node's source range, except when
                        // the input had no EOL's at the end at all, even then only one of these will be included
                        Inlines(), push(new ParaNode(popAsNode())), Test(BlankLine())

                )
        );
    }

    //************* FOOTNOTES ********************

    public Rule Footnote() {
        Var<FootnoteNode> node = new Var<FootnoteNode>();
        return Sequence(
                NonindentSpace(),
                NodeSequence(
                        Sequence(
                                FootnoteLabel(), Sp(), ':', Sp()
                        ),
                        push(node.setAndGet(new FootnoteNode(match()))),
                        Inlines(),
                        node.get().setFootnote(popAsNode())
                ),
                footnotes.add(node.get())
        );
    }

    public Rule FootnoteRef() {
        return NodeSequence(
                FootnoteLabel(), push(new FootnoteRefNode(match()))
        );
    }

    public Rule FootnoteLabel() {
        return Sequence("[^", ext(INTELLIJ_DUMMY_IDENTIFIER) ? ZeroOrMore(AlphanumericDashUnderDot()) : OneOrMore(AlphanumericDashUnderDot()), ']');
    }

    // vsch: #184 modified to only include trailing blank lines if there are more blocks for the blockquote following
    // otherwise don't include the blank lines, they are not part of the block quote
    public Rule BlockQuote() {
        StringBuilderVar inner = new StringBuilderVar();
        StringBuilderVar optional = new StringBuilderVar();
        return NodeSequence(
                OneOrMore(
                        CrossedOut(Sequence('>', Optional(' ')), inner), Line(inner),
                        ZeroOrMore(
                                TestNot('>'),
                                TestNot(BlankLine()),
                                Line(inner)
                        ),
                        Optional(Sequence(OneOrMore(BlankLine()), optional.append(match()), Test('>')), inner.append(optional.getString()) && optional.clearContents())
                ),

                // vsch: the block quotes won't parse into Para because they will not be followed by a blank line,
                // unless the last line of the block quote is an empty block-quote line: ">". We append one here to
                // take care of that possibility, now that we don't include blank lines after a block quote we add one extra
                inner.append("\n\n"),

                // trigger a recursive parsing run on the inner source we just built
                // and attach the root of the inner parses AST
                push(new BlockQuoteNode(withIndicesShifted(parseInternal(inner), (Integer) peek()).getChildren()))
        );
    }

    public Rule Verbatim() {
        StringBuilderVar text = new StringBuilderVar();
        StringBuilderVar line = new StringBuilderVar();
        return NodeSequence(
                OneOrMore(
                        ZeroOrMore(BlankLine(), line.append('\n')),
                        Indent(), push(currentIndex()),
                        OneOrMore(
                                FirstOf(
                                        Sequence('\t', line.append(repeat(' ', 4 - (currentIndex() - 1 - (Integer) peek()) % 4))),
                                        Sequence(NotNewline(), ANY, line.append(matchedChar()))
                                )
                        ),
                        Newline(),
                        text.appended(line.getString()).append('\n') && line.clearContents() && drop()
                ),
                push(new VerbatimNode(text.getString()))
        );
    }

    public Rule FencedCodeBlock() {
        StringBuilderVar text = new StringBuilderVar();
        Var<Integer> markerLength = new Var<Integer>();
        return NodeSequence(
                // vsch: test to see if what appears to be a code fence is just inline code
                // vsch: change to accept code fence with all blank lines
                CodeFence(markerLength),
                OneOrMore(
                        TestNot(CodeFence(markerLength)),
                        ZeroOrMore(TestNot(Newline()), ANY, text.append(matchedChar())),
                        Sequence(Newline(), text.append(matchedChar()))
                ),
                push(new VerbatimNode(text.getString(), popAsString())),
                CodeFence(markerLength), drop()
        );
    }

    @Cached
    public Rule CodeFence(Var<Integer> markerLength) {
        return Sequence(
                FirstOf(NOrMore('~', 3), NOrMore('`', 3)),
                (markerLength.isSet() && matchLength() == markerLength.get()) ||
                        (markerLength.isNotSet() && markerLength.set(matchLength())),
                Sp(),
                ZeroOrMore(TestNot(FirstOf(Newline(), '~', '`')), ANY), // GFM code type identifier but exclude fenced code markers
                push(match()),
                Newline()
        );
    }

    public Rule HorizontalRule() {
        return Sequence(
                NonindentSpace(),
                NodeSequence(
                        FirstOf(HorizontalRule('*'), HorizontalRule('-'), HorizontalRule('_')),
                        Sp(), Newline(),
                        (ext(RELAXEDHRULES) ? EMPTY : Test(BlankLine())),
                        push(new SimpleNode(Type.HRule))
                )
        );
    }

    public Rule HorizontalRule(char c) {
        return Sequence(c, Sp(), c, Sp(), c, ZeroOrMore(Sp(), c));
    }

    //************* HEADINGS ****************

    public Rule Heading() {
        return NodeSequence(FirstOf(AtxHeading(), SetextHeading()));
    }

    public Rule AtxHeading() {
        return Sequence(
                AtxStart(),
                //Optional(Sp()), // this should be just Sp() because it is already ZeroOrMore which means it is optional
                // ISSUE: #144, Add GFM style headers, space after # is required
                (ext(ATXHEADERSPACE) ? Spacechar() : EMPTY), Sp(),

                OneOrMore(AtxInline(), addAsChild()),
                wrapInAnchor(),
                Optional(Sp(), ZeroOrMore('#'), Sp()),
                Newline()
        );
    }

    public Rule AtxStart() {
        Var<HeaderNode> ref = new Var<HeaderNode>();

        return Sequence(
                FirstOf("######", "#####", "####", "###", "##", "#"),
                push(ref.setAndGet(new HeaderNode(match().length(), ext(Extensions.TOC)))),
                headers.add(ref.get())
        );
    }

    public Rule AtxInline() {
        return Sequence(
                TestNot(Newline()),
                TestNot(Sp(), ZeroOrMore('#'), Sp(), Newline()),
                Inline()
        );
    }

    // vsch: spaces are allowed after the Setext --- or === and before the newline
    //
    // straight from <http://docutils.sourceforge.net/mirror/setext/sermon_1+920315.etx.txt>
    //
    //  vsch: this is for the --- or === line
    //  (a) scan forward for a line that's made up of at least 2 leading
    //      dash characters (=minimum length; a practical consideration)
    //      with possible trailing white space (spaces, tabs and other
    //      defacto invisible [control] characters). If applicable, grep
    //      all such lines globally using the pattern "^--[-]*[\s\t]*$"
    //
    public Rule SetextHeading() {
        return Sequence(
                // test for successful setext heading before actually building it to reduce backtracking
                Test(OneOrMore(NotNewline(), ANY), Newline(), FirstOf(NOrMore('=', 3), NOrMore('-', 3)), Sp(), Newline()),
                FirstOf(SetextHeading1(), SetextHeading2())
        );
    }

    // vsch: #186 add isSetext flag to header node to distinguish header types
    public Rule SetextHeading1() {
        Var<HeaderNode> ref = new Var<HeaderNode>();

        return Sequence(
                SetextInline(), push(ref.setAndGet(new HeaderNode(1, ext(Extensions.TOC), popAsNode(), true))),
                ZeroOrMore(SetextInline(), addAsChild()),
                wrapInAnchor(),
                Sp(), Newline(), NOrMore('=', 3), Sp(), Newline(),
                headers.add(ref.get())
        );
    }

    public Rule SetextHeading2() {
        Var<HeaderNode> ref = new Var<HeaderNode>();

        return Sequence(
                SetextInline(), push(ref.setAndGet(new HeaderNode(2, ext(Extensions.TOC), popAsNode(), true))),
                ZeroOrMore(SetextInline(), addAsChild()),
                wrapInAnchor(),
                Sp(), Newline(), NOrMore('-', 3), Sp(), Newline(),
                headers.add(ref.get())
        );
    }

    public Rule SetextInline() {
        return Sequence(TestNot(Endline()), Inline());
    }

    class AnchorNodeInfo {
        public int startIndex = 0;
        public int endIndex = 0;
        public StringBuilder text = new StringBuilder();
    }

    // vsch: modified to accumulate text of all children
    public boolean wrapInAnchor() {
        if (ext(ANCHORLINKS | EXTANCHORLINKS)) {
            SuperNode node = (SuperNode) peek();
            List<Node> children = node.getChildren();
            if (ext(EXTANCHORLINKS)) {
                if (children.size() > 0) {
                    AnchorNodeInfo nodeInfo = new AnchorNodeInfo();
                    collectChildrensText(node, nodeInfo);
                    String text = nodeInfo.text.toString().trim();
                    if (text.length() > 0) {
                        if (ext(EXTANCHORLINKS_WRAP)) {
                            AnchorLinkNode anchor = new AnchorLinkNode(text, text);
                            anchor.setStartIndex(nodeInfo.startIndex);
                            anchor.setEndIndex(nodeInfo.endIndex);
                            children.clear();
                            children.add(0, anchor);
                        } else {
                            AnchorLinkNode anchor = new AnchorLinkNode(text, "");
                            anchor.setStartIndex(nodeInfo.startIndex);
                            anchor.setEndIndex(nodeInfo.endIndex);
                            children.add(0, anchor);
                        }
                    }
                }
            } else {
                if (children.size() == 1) {
                    Node child = children.get(0);
                    if (child instanceof TextNode) {
                        AnchorLinkNode anchor = new AnchorLinkNode(((TextNode) child).getText());
                        anchor.setStartIndex(child.getStartIndex());
                        anchor.setEndIndex(child.getEndIndex());
                        children.set(0, anchor);
                    }
                }
            }
        }

        return true;
    }

    public void collectChildrensText(SuperNode node, AnchorNodeInfo nodeInfo) {
        for (Node child : node.getChildren()) {
            // accumulate all the text
            if (child.getClass() == TextNode.class || child.getClass() == SpecialTextNode.class) {
                nodeInfo.text.append(((TextNode) child).getText());
                if (nodeInfo.startIndex == 0) {
                    nodeInfo.startIndex = child.getStartIndex();
                }
                nodeInfo.endIndex = child.getEndIndex();
            } else if (child instanceof SuperNode) {
                collectChildrensText((SuperNode) child, nodeInfo);
            }
        }
    }

    //************** Definition Lists ************

    public Rule DefinitionList() {
        return NodeSequence(
                // test for successful definition list match before actually building it to reduce backtracking
                TestNot(Spacechar()),
                Test(
                        OneOrMore(TestNot(BlankLine()), TestNot(DefListBullet()),
                                OneOrMore(NotNewline(), ANY), Newline()),
                        ZeroOrMore(BlankLine()),
                        DefListBullet()
                ),
                push(new DefinitionListNode()),
                OneOrMore(
                        push(new SuperNode()),
                        OneOrMore(DefListTerm(), addAsChild()),
                        OneOrMore(Definition(), addAsChild()),
                        ((SuperNode) peek(1)).getChildren().addAll(popAsNode().getChildren()),
                        Optional(BlankLine())
                )
        );
    }

    public Rule DefListTerm() {
        return NodeSequence(
                TestNot(Spacechar()),
                TestNot(DefListBullet()),
                push(new DefinitionTermNode()),
                OneOrMore(DefTermInline(), addAsChild()),
                Optional(':'),
                Newline()
        );
    }

    public Rule DefTermInline() {
        return Sequence(
                NotNewline(),
                TestNot(':', Newline()),
                Inline()
        );
    }

    public Rule Definition() {
        SuperNodeCreator itemNodeCreator = new SuperNodeCreator() {
            public SuperNode create(Node child) {
                return new DefinitionNode(child);
            }
        };
        return ListItem(DefListBullet(), itemNodeCreator);
    }

    public Rule DefListBullet() {
        return Sequence(NonindentSpace(), AnyOf(":~"), OneOrMore(Spacechar()));
    }

    //************* LISTS ****************

    public Rule BulletList() {
        if (ext(TASKLISTITEMS)) {
            // #185 GFM style task list items [ ] open task, [x] closed task handlings
            SuperNodeTaskItemCreator itemNodeCreator = new SuperNodeTaskItemCreator() {
                public SuperNode create(Node child) {
                    return new ListItemNode(child);
                }

                public SuperNode create(Node child, int taskType, String taskListMarker) {
                    return taskType == 0 ? new ListItemNode(child) : new TaskListNode(child, taskType == 2, taskListMarker);
                }
            };
            return NodeSequence(
                    TaskListItem(Bullet(), itemNodeCreator), push(new BulletListNode(popAsNode())),
                    ZeroOrMore(
                            //vsch: this one will absorb all blank lines but the last one preceding a list item otherwise two blank lines end a list and start a new one
                            ZeroOrMore(Test(BlankLine(), BlankLine()), BlankLine()),
                            TaskListItem(Bullet(), itemNodeCreator), addAsChild()
                    )
            );
        } else {
            SuperNodeCreator itemNodeCreator = new SuperNodeCreator() {
                public SuperNode create(Node child) {
                    return new ListItemNode(child);
                }
            };
            return NodeSequence(
                    ListItem(Bullet(), itemNodeCreator), push(new BulletListNode(popAsNode())),
                    ZeroOrMore(
                            //vsch: this one will absorb all blank lines but the last one preceding a list item otherwise two blank lines end a list and start a new one
                            ZeroOrMore(Test(BlankLine(), BlankLine()), BlankLine()),
                            ListItem(Bullet(), itemNodeCreator), addAsChild()
                    )
            );
        }
    }

    public Rule OrderedList() {
        SuperNodeCreator itemNodeCreator = new SuperNodeCreator() {
            public SuperNode create(Node child) {
                return new ListItemNode(child);
            }
        };
        return NodeSequence(
                ListItem(Enumerator(), itemNodeCreator), push(new OrderedListNode(popAsNode())),
                ZeroOrMore(
                        //vsch: this one will absorb all blank lines but the last one preceding a list item otherwise two blank lines end a list and start a new one
                        ZeroOrMore(Test(BlankLine(), BlankLine()), BlankLine()),
                        ListItem(Enumerator(), itemNodeCreator), addAsChild()
                )
        );
    }

    @Cached
    public Rule ListItem(Rule itemStart, SuperNodeCreator itemNodeCreator) {
        // for a simpler parser design we use a recursive parsing strategy for list items:
        // we collect a number of markdown source blocks for an item, run complete parsing cycle on these and attach
        // the roots of the inner parsing results AST to the outer AST tree
        StringBuilderVar block = new StringBuilderVar();
        StringBuilderVar temp = new StringBuilderVar();
        Var<Boolean> tight = new Var<Boolean>(false);
        Var<SuperNode> tightFirstItem = new Var<SuperNode>();
        return Sequence(
                push(getContext().getCurrentIndex()),
                FirstOf(CrossedOut(BlankLine(), block), tight.set(true)),
                CrossedOut(itemStart, block), Line(block),
                ZeroOrMore(
                        Optional(CrossedOut(Indent(), temp)),
                        TestNotItem(),
                        Line(temp),
                        block.append(temp.getString()) && temp.clearContents()
                ),

                tight.get() ? push(tightFirstItem.setAndGet(itemNodeCreator.create(parseListBlock(block)))) :
                        fixFirstItem((SuperNode) peek(1)) &&
                                push(itemNodeCreator.create(parseListBlock(block.appended('\n')))),
                ZeroOrMore(
                        //debugMsg("have a " + (tight.get() ? "tight" : "loose") + " list body at " + getContext().getCurrentIndex(), block.getString()),
                        push(getContext().getCurrentIndex()),
                        // it is not safe to gobble up the leading blank line if it is followed by another list item
                        // it must be left for it to determine its own looseness. Much safer to just test for it but not consume it.
                        FirstOf(Sequence(Test(BlankLine()), tight.set(false)), tight.set(true)),
                        ListItemIndentedBlocks(block),

                        //debugMsg("have a " + (tight.get() ? "tight" : "loose") + " list body", block.getString()),

                        // vsch: here we have a small problem. Even though a sub-item may be detected as loose, when it is parsed
                        // via the recursive call to parseListBlock, it will be tight because leading blank lines are pre-parsed
                        // out in Block Rule before getting to the ListItem rule. This happens if there is nothing else in the
                        // sub-item that will set its looseness and it is the first sub-item of its list. So we need to wrap it
                        // in a ParaNode to have its looseness properly reflected.
                        (tight.get() ? push(parseListBlock(block)) :
                                ((!ext(FORCELISTITEMPARA)) || tightFirstItem.isNotSet() || wrapFirstItemInPara(tightFirstItem.get())) &&
                                        push(wrapFirstSubItemInPara((SuperNode) parseListBlock(block.appended('\n'))))
                        ) && addAsChild()
                ),
                setListItemIndices()
        );
    }

    // vsch: #185 This handles the optional extension TASKLISTITEMS to parse and generate GFM task list styled items. These are
    // bullet items: * [ ] or * [x] , the space after the ] is not optional.
    @Cached
    public Rule TaskListItem(Rule itemStart, SuperNodeTaskItemCreator itemNodeCreator) {
        // for a simpler parser design we use a recursive parsing strategy for list items:
        // we collect a number of markdown source blocks for an item, run complete parsing cycle on these and attach
        // the roots of the inner parsing results AST to the outer AST tree
        StringBuilderVar block = new StringBuilderVar();
        StringBuilderVar taskListMarker = new StringBuilderVar();
        StringBuilderVar temp = new StringBuilderVar();
        Var<Boolean> tight = new Var<Boolean>(false);
        Var<Integer> taskType = new Var<Integer>(0);
        Var<SuperNode> tightFirstItem = new Var<SuperNode>();
        return Sequence(
                push(getContext().getCurrentIndex()),
                FirstOf(CrossedOut(BlankLine(), block), tight.set(true)),
                CrossedOut(itemStart, block), Optional(CrossedOut(FirstOf(Sequence("[ ] ", taskType.set(1)), Sequence(FirstOf("[x] ", "[X] "), taskType.set(2))), taskListMarker)),
                block.append(taskListMarker.getString()), Line(block),
                //debugMsg("have a " + taskType.get() + " task list body", block.getString()),
                ZeroOrMore(
                        Optional(CrossedOut(Indent(), temp)),
                        TestNotItem(),
                        Line(temp),
                        block.append(temp.getString()) && temp.clearContents()
                ),

                tight.get() ? push(tightFirstItem.setAndGet(itemNodeCreator.create(parseListBlock(block), taskType.get(), taskListMarker.getString()))) && taskListMarker.clearContents() :
                        fixFirstItem((SuperNode) peek(1)) &&
                                push(itemNodeCreator.create(parseListBlock(block.appended('\n')), taskType.get(), taskListMarker.getString())) && taskListMarker.clearContents(),
                ZeroOrMore(
                        //debugMsg("have a " + (tight.get() ? "tight" : "loose") + " list body at " + getContext().getCurrentIndex(), block.getString()),
                        push(getContext().getCurrentIndex()),
                        // it is not safe to gobble up the leading blank line if it is followed by another list item
                        // it must be left for it to determine its own looseness. Much safer to just test for it but not consume it.
                        FirstOf(Sequence(Test(BlankLine()), tight.set(false)), tight.set(true)),
                        ListItemIndentedBlocks(block),

                        //debugMsg("have a " + (tight.get() ? "tight" : "loose") + " list body", block.getString()),

                        // vsch: here we have a small problem. Even though a sub-item may be detected as loose, when it is parsed
                        // via the recursive call to parseListBlock, it will be tight because leading blank lines are pre-parsed
                        // out in Block Rule before getting to the ListItem rule. This happens if there is nothing else in the
                        // sub-item that will set its looseness and it is the first sub-item of its list. So we need to wrap it
                        // in a ParaNode to have its looseness properly reflected.
                        (tight.get() ? push(parseListBlock(block)) :
                                ((!ext(FORCELISTITEMPARA)) || tightFirstItem.isNotSet() || wrapFirstItemInPara(tightFirstItem.get())) &&
                                        push(wrapFirstSubItemInPara((SuperNode) parseListBlock(block.appended('\n'))))
                        ) && addAsChild()
                ),
                setListItemIndices()
        );
    }

    public Rule CrossedOut(Rule rule, StringBuilderVar block) {
        return Sequence(rule, appendCrossed(block));
    }

    public Rule CrossedOutLessOne(Rule rule, StringBuilderVar block) {
        return Sequence(rule, appendCrossedLessOne(block));
    }

    public Rule ListItemIndentedBlocks(StringBuilderVar block) {
        StringBuilderVar line = new StringBuilderVar();
        return Sequence(
                OneOrMore(
                        Sequence(
                                // vsch: Important! when accumulating text for recursive parsing it is critical that no characters, of the original text, are left out
                                // if you don't want them to be parsed replace them with crossed out marker, they will be stripped before parsing but the index to the
                                // source position will be correct. If you leave any characters out then the final index of the node will be offset by that many characters,
                                // giving an incorrect result for the AST node. Yes it is critical if you use the AST for syntax highlighting of the source.
                                ZeroOrMore(Sequence(CrossedOutLessOne(BlankLine(), line), line.append('\n'))),
                                CrossedOut(Indent(), line),
                                Line(line),

                                // take the rest of the block's lines, with or without indentations,
                                // but only if they are not a list item
                                ZeroOrMore(
                                        TestNot(BlankLine()),
                                        TestNotListItem(),
                                        Optional(CrossedOut(Indent(), line)),
                                        Line(line)
                                ),

                                block.append(line.getString()) && line.clearContents()
                        )
                ),

                // if there is a blank line then we append \n, but leave the blank line for the next item, just in case it needs it
                // to determine looseness, however this can only be done for the last block in the indented set, otherwise
                // if we do it for each block then code blocks will have their embedded blank lines doubled and index positions will be off.
                Optional(Test(BlankLine(), line.append('\n')))
        );
    }

    public Rule TestNotItem() {
        return TestNot(
                FirstOf(new ArrayBuilder<Rule>()
                        .add(Bullet(), Enumerator(), BlankLine(), HorizontalRule())
                        .addNonNulls(ext(DEFINITIONS) ? DefListBullet() : null)
                        .get()
                )
        );
    }

    public Rule TestNotListItem() {
        return TestNot(
                FirstOf(new ArrayBuilder<Rule>()
                        .add(Bullet(), Enumerator())
                        .addNonNulls(ext(DEFINITIONS) ? DefListBullet() : null)
                        .get()
                )
        );
    }

    public Rule Enumerator() {
        return Sequence(NonindentSpace(), OneOrMore(Digit()), '.', OneOrMore(Spacechar()));
    }

    public Rule Bullet() {
        return Sequence(TestNot(HorizontalRule()), NonindentSpace(), AnyOf("+*-"), OneOrMore(Spacechar()));
    }

    //************* LIST ITEM ACTIONS ****************

    boolean appendCrossed(StringBuilderVar block) {
        int iMax = matchLength();
        for (int i = 0; i < iMax; i++) {
            block.append(CROSSED_OUT);
        }
        return true;
    }

    boolean appendCrossedLessOne(StringBuilderVar block) {
        int iMax = matchLength() - 1;
        for (int i = 0; i < iMax; i++) {
            block.append(CROSSED_OUT);
        }
        return true;
    }

    Node parseListBlock(StringBuilderVar block) {
        Context<Object> context = getContext();
        Node innerRoot = parseInternal(block);
        setContext(context); // we need to save and restore the context since we might be recursing
        block.clearContents();
        //debugMsg("parsed list block " + innerRoot.toString() + " adjusting indices by " + getContext().getValueStack().peek(), block.getString());
        return withIndicesShifted(innerRoot, (Integer) context.getValueStack().pop());
    }

    Node withIndicesShifted(Node node, int delta) {
        if (delta != 0) {
            ((AbstractNode) node).shiftIndices(delta);

            // vsch: a few hours trying to figure out why the AST is wrong for RefLinkNode.referenceKey
            // need to shift any nodes contained in other notes that are not children, each node should do its own shifting for
            // children and other nodes it contains as non-children, that way this code will always be correct.
            //for (Node subNode : node.getChildren()) {
            //    withIndicesShifted(subNode, delta);
            //}
        }
        return node;
    }

    boolean fixFirstItem(SuperNode listNode) {
        List<Node> items = listNode.getChildren();
        if (items.size() == 1 && items.get(0) instanceof ListItemNode) {
            wrapFirstItemInPara((SuperNode) items.get(0));
        }
        return true;
    }

    boolean wrapFirstItemInPara(SuperNode item) {
        Node firstItemFirstChild = item.getChildren().get(0);
        ParaNode paraNode = new ParaNode(firstItemFirstChild.getChildren());
        paraNode.setStartIndex(firstItemFirstChild.getStartIndex());
        paraNode.setEndIndex(firstItemFirstChild.getEndIndex());
        // vsch: wrap the para in RootNode so that it is identical to the rest of the list items if they are loose, otherwise it creates differences in html serialization of task items
        RootNode rootNode = new RootNode();
        rootNode.setStartIndex(paraNode.getStartIndex());
        rootNode.setEndIndex(paraNode.getEndIndex());
        rootNode.getChildren().add(paraNode);
        item.getChildren().set(0, rootNode);
        return true;
    }

    SuperNode wrapFirstSubItemInPara(SuperNode item) {
        Node firstItemFirstChild = item.getChildren().get(0);
        if (firstItemFirstChild.getChildren().size() == 1) {
            Node firstGrandChild = firstItemFirstChild.getChildren().get(0);
            if (firstGrandChild instanceof ListItemNode) {
                wrapFirstItemInPara((SuperNode) firstGrandChild);
            }
        }
        return item;
    }

    boolean setListItemIndices() {
        SuperNode listItem = (SuperNode) getContext().getValueStack().peek();
        List<Node> children = listItem.getChildren();
        listItem.setStartIndex(children.get(0).getStartIndex());
        listItem.setEndIndex(children.get(children.size() - 1).getEndIndex());
        return true;
    }

    //************* HTML BLOCK ****************

    public Rule HtmlBlock() {
        return NodeSequence(
                FirstOf(HtmlBlockInTags(), HtmlComment(), HtmlBlockSelfClosing()),
                push(new HtmlBlockNode(ext(SUPPRESS_HTML_BLOCKS) ? "" : match())),
                BlankLine()
        );
    }

    public Rule HtmlBlockInTags() {
        StringVar tagName = new StringVar();
        return Sequence(
                Test(HtmlBlockOpen(tagName)), // get the type of tag if there is one
                HtmlTagBlock(tagName) // specifically match that type of tag
        );
    }

    @Cached
    public Rule HtmlTagBlock(StringVar tagName) {
        return Sequence(
                HtmlBlockOpen(tagName),
                ZeroOrMore(
                        FirstOf(
                                HtmlTagBlock(tagName),
                                Sequence(TestNot(HtmlBlockClose(tagName)), ANY)
                        )
                ),
                HtmlBlockClose(tagName)
        );
    }

    public Rule HtmlBlockSelfClosing() {
        StringVar tagName = new StringVar();
        return Sequence('<', Spn1(), DefinedHtmlTagName(tagName), Spn1(), ZeroOrMore(HtmlAttribute()), Optional('/'),
                Spn1(), '>');
    }

    public Rule HtmlBlockOpen(StringVar tagName) {
        return Sequence('<', Spn1(), DefinedHtmlTagName(tagName), Spn1(), ZeroOrMore(HtmlAttribute()), '>');
    }

    @DontSkipActionsInPredicates
    public Rule HtmlBlockClose(StringVar tagName) {
        return Sequence('<', Spn1(), '/', OneOrMore(Alphanumeric()), match().equals(tagName.get()), Spn1(), '>');
    }

    @Cached
    public Rule DefinedHtmlTagName(StringVar tagName) {
        return Sequence(
                OneOrMore(Alphanumeric()),
                tagName.isSet() && match().equals(tagName.get()) ||
                        tagName.isNotSet() && tagName.set(match().toLowerCase()) && isHtmlTag(tagName.get())
        );
    }

    public boolean isHtmlTag(String string) {
        return HTML_TAGS.contains(string);
    }

    protected static final Set<String> HTML_TAGS = new HashSet<String>(Arrays.asList(
            // https://developer.mozilla.org/en/docs/Web/HTML/Element
            // Basic elements
            "html",
            // Document metadata
            "base", "head", "link", "meta", "style", "title",
            // Content sectioning
            "address", "article", "aside", "body", "footer", "header", "h1", "h2", "h3", "h4", "h5", "h6", "hgroup", "nav", "section",
            // Text bontent
            "dd", "div", "dl", "dt", "figcaption", "figure", "hr", "li", "main", "ol", "p", "pre", "ul",
            // Inline text semantics
            // "abbr" not included, breaks some tests
            "a", "b", "bdi", "bdo", "br", "cite", "code", "data", "dfn", "em", "i", "kbd", "mark", "q", "rp", "rt",
            "rtc", "ruby", "s", "samp", "small", "span", "strong", "sub", "sup", "time", "u", "var", "wbr",
            // Image & multimedia
            "area", "audio", "img", "map", "track", "video",
            // Embedded content
            "embed", "iframe", "object", "param", "source",
            // Scripting
            "canvas", "noscript", "script",
            // Edits
            "del", "ins",
            // Table content
            "caption", "col", "colgroup", "table", "tbody", "td", "tfoot", "th", "thead", "tr",
            // Forms
            "button", "datalist", "fieldset", "form", "input", "keygen", "label", "legend", "meter",
            "optgroup", "option", "output", "progress", "select", "textarea",
            // Interactive elements
            "details", "dialog", "menu", "menuitem", "summary",
            // Web Components
            "content", "decorator", "element", "shadow", "template",
            // Obsolete and deprecated elements
            "acronym", "applet", "basefont", "big", "blink", "center", "dir", "frame", "frameset",
            "isindex", "listing", "noembed", "plaintext", "spacer", "strike", "tt", "xmp"
    ));

    //************* INLINES ****************

    public Rule Inlines() {
        return NodeSequence(
                InlineOrIntermediateEndline(), push(new SuperNode(popAsNode())),
                ZeroOrMore(InlineOrIntermediateEndline(), addAsChild()),
                Optional(Endline(), drop())
        );
    }

    public Rule InlineOrIntermediateEndline() {
        return FirstOf(
                Sequence(TestNot(Endline()), Inline()),
                Sequence(Endline(), Test(Inline()))
        );
    }

    @MemoMismatches
    public Rule Inline() {
        return Sequence(
                checkForParsingTimeout(),
                FirstOf(Link(), NonLinkInline())
        );
    }

    public Rule NonLinkInline() {
        return FirstOf(new ArrayBuilder<Rule>()
                .add(plugins.getInlinePluginRules())
                .add(Str(), Endline(), UlOrStarLine(), Spaces(), StrongOrEmph(), Image(), Code(), InlineHtml(), Entity(), EscapedChar())
                .addNonNulls(ext(QUOTES) ? new Rule[] { SingleQuoted(), DoubleQuoted(), DoubleAngleQuoted() } : null)
                .addNonNulls(ext(SMARTS) ? new Rule[] { Smarts() } : null)
                .addNonNulls(ext(STRIKETHROUGH) ? new Rule[] { Strike() } : null)
                .addNonNulls(ext(FOOTNOTES) ? new Rule[] { FootnoteRef() } : null)
                .add(Symbol())
                .get()
        );
    }

    @MemoMismatches
    public Rule Endline() {
        return NodeSequence(FirstOf(LineBreak(), TerminalEndline(), NormalEndline()));
    }

    public Rule LineBreak() {
        return Sequence("  ", NormalEndline(), poke(new SimpleNode(Type.Linebreak)));
    }

    public Rule TerminalEndline() {
        return NodeSequence(Sp(), Newline(), Test(EOI), push(new TextNode("\n")));
    }

    public Rule NormalEndline() {
        return Sequence(
                Sp(), Newline(),
                TestNot(
                        FirstOf(
                                BlankLine(),
                                '>',
                                AtxStart(),
                                Sequence(ZeroOrMore(NotNewline(), ANY), Newline(),
                                        FirstOf(NOrMore('=', 3), NOrMore('-', 3)), Newline()),

                                FencedCodeBlock()
                        )
                ),
                ext(HARDWRAPS) ? toRule(push(new SimpleNode(Type.Linebreak))) : toRule(push(new TextNode(" ")))
        );
    }

    //************* EMPHASIS / STRONG ****************

    @MemoMismatches
    public Rule UlOrStarLine() {
        // This keeps the parser from getting bogged down on long strings of '*', '_' or '~',
        // or strings of '*', '_' or '~' with space on each side:
        return NodeSequence(
                FirstOf(CharLine('_'), CharLine('*'), CharLine('~')),
                push(new TextNode(match()))
        );
    }

    public Rule CharLine(char c) {
        return FirstOf(NOrMore(c, 4), Sequence(Spacechar(), OneOrMore(c), Test(Spacechar())));
    }

    public Rule StrongOrEmph() {
        return Sequence(
                Test(AnyOf("*_")),
                FirstOf(Strong(), Emph())
        );
    }

    public Rule Emph() {
        return NodeSequence(FirstOf(EmphOrStrong("*"), EmphOrStrong("_")));
    }

    public Rule Strong() {
        return NodeSequence(FirstOf(EmphOrStrong("**"), EmphOrStrong("__")));
    }

    // vsch: TODO: test for unclosed strikethrough sequence carrying through the isClosed attribute, as soon as I can figure out how
    public Rule Strike() {
        // vsch: we need to preserve isClosed() attribute of the StrongEmphSuperNode, otherwise we can have
        // a strike sequence which is not closed and it makes it difficult to split out the lead-in/termination
        // characters for syntax highlighting see: https://github.com/vsch/idea-multimarkdown for an example
        return NodeSequence(
                EmphOrStrong("~~"),
                push(new StrikeNode((StrongEmphSuperNode) popAsNode()))
        );
    }

    @Cached
    public Rule EmphOrStrong(String chars) {
        return Sequence(
                Test(mayEnterEmphOrStrong(chars)),
                EmphOrStrongOpen(chars),
                push(new StrongEmphSuperNode(chars)),
                OneOrMore(
                        TestNot(EmphOrStrongClose(chars)),
                        Inline(),
                        FirstOf(
                                Sequence(
                                        //if current inline ends with a closing char for a current strong node:
                                        Test(isStrongCloseCharStolen(chars)),
                                        //and composes a valid strong close:
                                        chars.substring(0, 1),
                                        //which is not followed by another closing char (e.g. in __strong _nestedemph___):
                                        TestNot(chars.substring(0, 1)),
                                        //degrade current inline emph to unclosed and mark current strong node for closing
                                        stealBackStrongCloseChar()
                                ),
                                addAsChild()
                        )
                ),
                Optional(Sequence(EmphOrStrongClose(chars), setClosed()))
        );
    }

    public Rule EmphOrStrongOpen(String chars) {
        return Sequence(
                TestNot(CharLine(chars.charAt(0))),
                chars,
                TestNot(Spacechar()),
                NotNewline()
        );
    }

    @Cached
    public Rule EmphOrStrongClose(String chars) {
        return Sequence(
                Test(isLegalEmphOrStrongClosePos()),
                FirstOf(
                        Sequence(
                                Test(ValidEmphOrStrongCloseNode.class.equals(peek(0).getClass())),
                                drop()
                        ),
                        Sequence(
                                TestNot(Spacechar()),
                                NotNewline(),
                                chars,
                                FirstOf((ext(RELAXED_STRONG_EMPHASIS_RULES) ? chars.charAt(0) != '_' : chars.length() == 2), TestNot(Alphanumeric()))
                        )
                )
        );
    }

    /**
     * This method checks if the parser can enter an emph or strong sequence
     * Emph only allows Strong as direct child, Strong only allows Emph as
     * direct child.
     */
    protected boolean mayEnterEmphOrStrong(String chars) {
        if (!isLegalEmphOrStrongStartPos(chars)) {
            return false;
        }

        Object parent = peek(2);
        boolean isStrong = (chars.length() == 2);

        if (StrongEmphSuperNode.class.equals(parent.getClass())) {
            if (((StrongEmphSuperNode) parent).isStrong() == isStrong)
                return false;
        }
        return true;
    }

    /**
     * This method checks if current position is a legal start position for a
     * strong or emph sequence by checking the last parsed character(-sequence).
     */
    protected boolean isLegalEmphOrStrongStartPos(String chars) {
        if (currentIndex() == 0)
            return true;

        Object lastItem = peek(1);
        Class<?> lastClass = lastItem.getClass();

        SuperNode supernode;
        while (SuperNode.class.isAssignableFrom(lastClass)) {
            supernode = (SuperNode) lastItem;

            if (supernode.getChildren().size() < 1)
                return true;

            lastItem = supernode.getChildren().get(supernode.getChildren().size() - 1);
            lastClass = lastItem.getClass();
        }

        if (ext(RELAXED_STRONG_EMPHASIS_RULES)) {
            return (TextNode.class.equals(lastClass) && allowStrongEmphasisStart(chars, ((TextNode) lastItem).getText()))
                    || (SpecialTextNode.class.equals(lastClass) && allowStrongEmphasisStart(chars, ((SpecialTextNode) lastItem).getText()))
                    || (SimpleNode.class.equals(lastClass))
                    || (java.lang.Integer.class.equals(lastClass));
        } else {
            return (TextNode.class.equals(lastClass) && ((TextNode) lastItem).getText().endsWith(" "))
                    || (SimpleNode.class.equals(lastClass))
                    || (java.lang.Integer.class.equals(lastClass));
        }
    }

    protected boolean allowStrongEmphasisStart(String chars, String text) {
        int pos = text.length() - 1;
        char c = pos >= 0 ? text.charAt(pos) : '_';
        return chars.charAt(0) != '_' || (!Character.isLetterOrDigit(c) && c != '_');
    }

    /**
     * Mark the current StrongEmphSuperNode as closed sequence
     */
    protected boolean setClosed() {
        StrongEmphSuperNode node = (StrongEmphSuperNode) peek();
        node.setClosed(true);
        return true;
    }

    /**
     * This method checks if current parent is a strong parent based on param `chars`. If so, it checks if the
     * latest inline node to be added as child does not end with a closing character of the parent. When this
     * is true, a next test should check if the closing character(s) of the child should become (part of) the
     * closing character(s) of the parent.
     */
    protected boolean isStrongCloseCharStolen(String chars) {
        if (chars.length() < 2)
            return false;

        Object childClass = peek().getClass();

        //checks if last `inline` to be added as child is not a StrongEmphSuperNode
        //that eats up a closing character for the parent StrongEmphSuperNode
        if (StrongEmphSuperNode.class.equals(childClass)) {
            StrongEmphSuperNode child = (StrongEmphSuperNode) peek();
            if (!child.isClosed())
                return false;

            if (child.getChars().endsWith(chars.substring(0, 1))) {
                //The nested child ends with closing char for the parent, allow stealing it back
                return true;
            }
        }

        return false;
    }

    /**
     * Steals the last close char by marking a previously closed emph/strong node as unclosed.
     */
    protected boolean stealBackStrongCloseChar() {
        StrongEmphSuperNode child = (StrongEmphSuperNode) peek();
        child.setClosed(false);
        addAsChild();
        //signal parser to close StrongEmphSuperNode in next check for close char
        push(new ValidEmphOrStrongCloseNode());
        return true;
    }

    /**
     * This method checks if the last parsed character or sequence is a valid prefix for a closing char for
     * an emph or strong sequence.
     */
    protected boolean isLegalEmphOrStrongClosePos() {
        Object lastItem = peek();
        if (StrongEmphSuperNode.class.equals(lastItem.getClass())) {
            List<Node> children = ((StrongEmphSuperNode) lastItem).getChildren();

            if (children.size() < 1)
                return true;

            lastItem = children.get(children.size() - 1);
            Class<?> lastClass = lastItem.getClass();

            if (TextNode.class.equals(lastClass))
                return !((TextNode) lastItem).getText().endsWith(" ");

            if (SimpleNode.class.equals(lastClass))
                return !((SimpleNode) lastItem).getType().equals(SimpleNode.Type.Linebreak);
        }
        return true;
    }

    //************* LINKS ****************

    public Rule Image() {
        return NodeSequence(
                '!', ImageAlt(),
                FirstOf(ext(MULTI_LINE_IMAGE_URLS) ? MultiLineURLImage() : NOTHING, ExplicitLink(true), ReferenceLink(true))
        );
    }

    // vsch: a real hack to get multi-line images, treats everything till closing ) as one chunk of text, blank lines and all
    // vsch: TEST: need tests for this.
    public Rule MultiLineURLImage() {
        return Sequence(
                Spn1(), '(', Sp(),
                MultiLineLinkSource(),
                MultiLineImageEnd(),
                push(new ExpImageNode(popAsString(), popAsString(), popAsNode()))
        );
    }

    @Cached
    public Rule MultiLineImageEnd() {
        return Sequence(
                NonindentSpace(), FirstOf(Sequence(LinkTitle(), Sp()), push("")), ')', Sp(), Test(Newline())
        );
    }

    @Cached
    public Rule MultiLineLinkSource() {
        StringBuilderVar url = new StringBuilderVar();
        return Sequence(
                OneOrMore(
                        FirstOf(
                                Sequence('\\', AnyOf("()?"), url.append(matchedChar())),
                                Sequence(TestNot(AnyOf("()?")), Nonspacechar(), url.append(matchedChar()))
                        )
                ),
                Sequence(Sequence('?', Sp(), Newline()), url.append(match())),
                OneOrMore(
                        TestNot(MultiLineImageEnd()),
                        ZeroOrMore(Sequence(NotNewline(), ANY, url.append(matchedChar()))),
                        Sequence(Newline(), url.append(match()))
                ),
                push(url.getString()), url.clearContents()
        );
    }

    @MemoMismatches
    public Rule Link() {
        return NodeSequence(
                FirstOf(new ArrayBuilder<Rule>()
                        .addNonNulls(ext(WIKILINKS) ? new Rule[] { WikiLink() } : null)
                        .add(Sequence(Label(), FirstOf(ExplicitLink(false), ReferenceLink(false))))
                        .add(AutoLink())
                        .get()
                )
        );
    }

    @Cached
    public Rule ExplicitLink(boolean image) {
        return Sequence(
                Spn1(), '(', Sp(),
                LinkSource(),
                Spn1(), FirstOf(LinkTitle(), push("")),
                Sp(), ')',
                push(image ?
                        new ExpImageNode(popAsString(), popAsString(), popAsNode()) :
                        new ExpLinkNode(popAsString(), popAsString(), popAsNode())
                )
        );
    }

    public Rule ReferenceLink(boolean image) {
        return Sequence(
                FirstOf(
                        Sequence(
                                Spn1(), push(match()),
                                FirstOf(
                                        Label(), // regular reference link
                                        // vsch: without a DUMMY_REFERENCE_KEY there is no way to tell [ ] from [ ][] from the AST
                                        Sequence("[]", push(ext(DUMMY_REFERENCE_KEY) ? ReferenceNode.DUMMY_REFERENCE_KEY : null)) // implicit reference link
                                )
                        ),
                        Sequence(push(null), push(null)) // implicit reference link without trailing []
                ),
                push(image ?
                        new RefImageNode((SuperNode) popAsNode(), popAsString(), popAsNode()) :
                        new RefLinkNode((SuperNode) popAsNode(), popAsString(), popAsNode())
                )
        );
    }

    @Cached
    public Rule LinkSource() {
        StringBuilderVar url = new StringBuilderVar();
        return FirstOf(
                Sequence('(', LinkSource(), ')'),
                Sequence('<', LinkSource(), '>'),
                Sequence(
                        OneOrMore(
                                FirstOf(
                                        Sequence('\\', AnyOf("()"), url.append(matchedChar())),
                                        Sequence(TestNot(AnyOf("()>")), Nonspacechar(), url.append(matchedChar()))
                                )
                        ),
                        push(url.getString())
                ),
                push("")
        );
    }

    public Rule LinkTitle() {
        return FirstOf(LinkTitle('\''), LinkTitle('"'));
    }

    public Rule LinkTitle(char delimiter) {
        return Sequence(
                delimiter,
                ZeroOrMore(TestNot(delimiter, Sp(), FirstOf(')', Newline())), NotNewline(), ANY),
                push(match()),
                delimiter
        );
    }

    public Rule AutoLink() {
        return Sequence(
                ext(AUTOLINKS) ? Optional('<') : Ch('<'),
                FirstOf(AutoLinkUrl(), AutoLinkEmail()),
                ext(AUTOLINKS) ? Optional('>') : Ch('>')
        );
    }

    public Rule WikiLink() {
        return Sequence(
                "[[",
                ext(INTELLIJ_DUMMY_IDENTIFIER) ? ZeroOrMore(TestNot(FirstOf("]]", BlankLine())), ANY) : OneOrMore(TestNot(FirstOf("]]", BlankLine())), ANY), // might have to restrict from ANY
                push(new WikiLinkNode(match())),
                "]]"
        );
    }

    public Rule AutoLinkUrl() {
        return Sequence(
                Sequence(OneOrMore(Letter()), "://", AutoLinkEnd()),
                push(new AutoLinkNode(match()))
        );
    }

    public Rule AutoLinkEmail() {
        return Sequence(
                Sequence(OneOrMore(FirstOf(Alphanumeric(), AnyOf("-+_."))), '@', AutoLinkEnd()),
                push(new MailLinkNode(match()))
        );
    }

    public Rule AutoLinkEnd() {
        return OneOrMore(
                TestNot(Newline()),
                ext(AUTOLINKS) ?
                        TestNot(
                                FirstOf(
                                        // vsch: don't allow emphasis or strikethrough in autolinks otherwise **name@address.com** gets parsed as ** 'name@address.com**' instead of ** 'name@address.com' **
                                        AnyOf(ext(STRIKETHROUGH) ? "<*~>" : "<*>"),
                                        Sequence(Optional(AnyOf(".,;:)}]\"'")), FirstOf(Spacechar(), Newline()))
                                )
                        ) :
                        TestNot('>'),
                ANY
        );
    }

    //************* REFERENCE ****************

    public Rule NonAutoLink() {
        return NodeSequence(Sequence(Label(), FirstOf(ExplicitLink(false), ReferenceLink(false))));
    }

    public Rule NonAutoLinkInline() {
        return FirstOf(NonAutoLink(), NonLinkInline());
    }

    // can't treat labels the same as the image alt since the image alt should be able to empty.
    public Rule ImageAlt() {
        return Sequence(
                '[',
                checkForParsingTimeout(),
                push(new SuperNode()),
                ZeroOrMore(TestNot(']'), NonLinkInline(), addAsChild()),
                ']'
        );
    }

    public Rule Label() {
        return
                FirstOf(
                        Sequence(
                                '[',
                                (ext(FOOTNOTES) ? TestNot('^') : EMPTY),
                                checkForParsingTimeout(),
                                push(new SuperNode()),
                                OneOrMore(TestNot(']'), NonAutoLinkInline(), addAsChild()),
                                ']'
                        ),
                        ext(INTELLIJ_DUMMY_IDENTIFIER) ?
                                Sequence("[]", Test(FirstOf(Sequence(':', Spn1()), NonEmptyLabel(), "[]", Sequence(Spn1(), '(', Sp(),
                                        LinkSource(),
                                        Spn1(), FirstOf(LinkTitle(), push("")),
                                        Sp(), ')'))),
                                        push(new SuperNode())
                                )
                                : NOTHING
                );
    }

    public Rule NonEmptyLabel() {
        return
                Sequence(
                        '[',
                        (ext(FOOTNOTES) ? TestNot('^') : EMPTY),
                        checkForParsingTimeout(),
                        push(new SuperNode()),
                        OneOrMore(TestNot(']'), NonLinkInline(), addAsChild()),
                        ']'
                );
    }

    // here we exclude the EOL at the end from the node's text range
    public Rule Reference() {
        return Sequence(
                // vsch: moved NonindentSpace() outside of the ReferenceNode range, otherwise the leading spaces were included as part of the node range.
                NonindentSpace(), ReferenceNoEOL(),
                Newline()
        );
    }

    public Rule ReferenceNoEOL() {
        Var<ReferenceNode> ref = new Var<ReferenceNode>();
        return NodeSequence(
                Label(), push(ref.setAndGet(new ReferenceNode(popAsNode()))),
                ':', Spn1(), RefSrc(ref),
                Sp(), Optional(RefTitle(ref)),
                Sp(),

                // make range not include  the EOL
                Test(Newline()),
                references.add(ref.get())
        );
    }

    public Rule RefSrc(Var<ReferenceNode> ref) {
        return FirstOf(
                Sequence('<', RefSrcContent(ref), '>'),
                RefSrcContent(ref)
        );
    }

    public Rule RefSrcContent(Var<ReferenceNode> ref) {
        return Sequence(OneOrMore(TestNot('>'), Nonspacechar()), ref.get().setUrl(match()));
    }

    public Rule RefTitle(Var<ReferenceNode> ref) {
        return FirstOf(RefTitle('\'', '\'', ref), RefTitle('"', '"', ref), RefTitle('(', ')', ref));
    }

    public Rule RefTitle(char open, char close, Var<ReferenceNode> ref) {
        return Sequence(
                open,
                ZeroOrMore(TestNot(close, Sp(), Newline()), NotNewline(), ANY),
                ref.get().setTitle(match()),
                close
        );
    }

    //************* CODE ****************

    public Rule Code() {
        return NodeSequence(
                Test('`'),
                FirstOf(
                        Code(Ticks(1)),
                        Code(Ticks(2)),
                        Code(Ticks(3)),
                        Code(Ticks(4)),
                        Code(Ticks(5))
                )
        );
    }

    public Rule Code(Rule ticks) {
        return Sequence(
                ticks, Sp(),
                OneOrMore(
                        FirstOf(
                                Sequence(TestNot('`'), Nonspacechar()),
                                Sequence(TestNot(ticks), OneOrMore('`')),
                                Sequence(TestNot(Sp(), ticks), FirstOf(Spacechar(), Sequence(Newline(), TestNot(BlankLine()))))
                        )
                ),
                push(new CodeNode(match())),
                Sp(), ticks
        );
    }

    public Rule Ticks(int count) {
        return Sequence(repeat('`', count), TestNot('`'));
    }

    //************* RAW HTML ****************

    public Rule InlineHtml() {
        return NodeSequence(
                FirstOf(HtmlComment(), HtmlTag()),
                push(new InlineHtmlNode(ext(SUPPRESS_INLINE_HTML) ? "" : match()))
        );
    }

    public Rule HtmlComment() {
        return Sequence("<!--", ZeroOrMore(TestNot("-->"), ANY), "-->");
    }

    public Rule HtmlTag() {
        return Sequence('<', Spn1(), Optional('/'), OneOrMore(Alphanumeric()), Spn1(), ZeroOrMore(HtmlAttribute()),
                Optional('/'), Spn1(), '>');
    }

    public Rule HtmlAttribute() {
        return Sequence(
                OneOrMore(FirstOf(Alphanumeric(), '-', '_')),
                Spn1(),
                Optional('=', Spn1(), FirstOf(Quoted(), OneOrMore(TestNot('>'), Nonspacechar()))),
                Spn1()
        );
    }

    public Rule Quoted() {
        return FirstOf(
                Sequence('"', ZeroOrMore(TestNot('"'), ANY), '"'),
                Sequence('\'', ZeroOrMore(TestNot('\''), ANY), '\'')
        );
    }

    //************* LINES ****************

    public Rule BlankLine() {
        return Sequence(Sp(), Newline());
    }

    public Rule Line(StringBuilderVar sb) {
        return Sequence(
                Sequence(ZeroOrMore(NotNewline(), ANY), Newline()),
                sb.append(match())
        );
    }

    //************* ENTITIES ****************

    public Rule Entity() {
        return NodeSequence(
                Sequence('&', FirstOf(HexEntity(), DecEntity(), CharEntity()), ';'),
                push(new TextNode(match()))
        );
    }

    public Rule HexEntity() {
        return Sequence('#', IgnoreCase('x'), OneOrMore(FirstOf(Digit(), CharRange('a', 'f'), CharRange('A', 'F'))));
    }

    public Rule DecEntity() {
        return Sequence('#', OneOrMore(Digit()));
    }

    public Rule CharEntity() {
        return OneOrMore(Alphanumeric());
    }

    //************* BASICS ****************

    public Rule Str() {
        return NodeSequence(OneOrMore(NormalChar()), push(new TextNode(match())));
    }

    public Rule Spaces() {
        return NodeSequence(OneOrMore(Spacechar()), push(new TextNode(match())));
    }

    public Rule Spn1() {
        return Sequence(Sp(), Optional(Newline(), Sp()));
    }

    public Rule Sp() {
        return ZeroOrMore(Spacechar());
    }

    public Rule Spacechar() {
        return AnyOf(" \t");
    }

    public Rule Nonspacechar() {
        return Sequence(TestNot(Spacechar()), NotNewline(), ANY);
    }

    @MemoMismatches
    public Rule NormalChar() {
        return Sequence(TestNot(SpecialChar()), TestNot(Spacechar()), NotNewline(), ANY);
    }

    public Rule EscapedChar() {
        // Previously all "*_`&[]<>!#\\'\".+-(){}:|~" were treated as escapable
        // only escape special characters as per John Gruber's list: "\\`*_{}[]()#+-.!" (plus <>&: for HTML escapes)
        // and additional extensions otherwise we will unnecessarily eat up \ when the extension is not turned on
        return NodeSequence('\\', EscapableChar(), push(new SpecialTextNode(match())));
    }

    public Rule Symbol() {
        return NodeSequence(SpecialChar(), push(new SpecialTextNode(match())));
    }

    public Rule SpecialChar() {
        String chars = "*_`&[]<>!#\\";
        if (ext(QUOTES)) {
            chars += "'\"";
        }
        if (ext(SMARTS)) {
            chars += ".-";
        }
        if (ext(AUTOLINKS)) {
            chars += "(){}";
        }
        if (ext(DEFINITIONS)) {
            chars += ":";
        }
        if (ext(FOOTNOTES)) {
            chars += "^";
        }
        if (ext(TABLES)) {
            chars += "|";
        }

        // Issue: #131 allow strikethrough to work without defs or fenced code extension
        if (ext(DEFINITIONS | FENCED_CODE_BLOCKS | STRIKETHROUGH)) {
            chars += "~";
        }
        for (Character ch : plugins.getSpecialChars()) {
            if (!chars.contains(ch.toString())) {
                chars += ch;
            }
        }
        return AnyOf(chars);
    }

    // make these as per john grubber's original list + <>& + selected extensions
    public Rule EscapableChar() {
        // John Gruber's list: "\\`*_{}[]()#+-.!"
        String chars = "\\`*_{}[]()#+-.!&<>";
        if (ext(QUOTES)) {
            chars += "'\"";
        }
        if (ext(SMARTS)) {
            chars += ".-";
        }
        if (ext(AUTOLINKS)) {
            chars += "(){}";
        }
        if (ext(DEFINITIONS)) {
            chars += ":";
        }
        if (ext(FOOTNOTES)) {
            chars += "^";
        }
        if (ext(TABLES)) {
            chars += "|";
        }

        // Issue: #131 allow strikethrough to work without defs or fenced code extension
        if (ext(DEFINITIONS | FENCED_CODE_BLOCKS | STRIKETHROUGH)) {
            chars += "~";
        }
        for (Character ch : plugins.getSpecialChars()) {
            if (!chars.contains(ch.toString())) {
                chars += ch;
            }
        }
        return AnyOf(chars);
    }

    public Rule NotNewline() {
        return TestNot(AnyOf("\n\r"));
    }

    public Rule Newline() {
        return FirstOf('\n', Sequence('\r', Optional('\n')));
    }

    public Rule NonindentSpace() {
        return FirstOf("   ", "  ", " ", EMPTY);
    }

    public Rule Indent() {
        return FirstOf('\t', "    ");
    }

    public Rule Alphanumeric() {
        return FirstOf(Letter(), Digit());
    }

    public Rule AlphanumericDashUnderDot() {
        return FirstOf(Letter(), Digit(), '-', '_', '.');
    }

    public Rule Letter() {
        return ext(INTELLIJ_DUMMY_IDENTIFIER) ? FirstOf(CharRange('a', 'z'), CharRange('A', 'Z'), '\u001F')
                : FirstOf(CharRange('a', 'z'), CharRange('A', 'Z'));
    }

    public Rule Digit() {
        return CharRange('0', '9');
    }

    //************* ABBREVIATIONS ****************

    public Rule Abbreviation() {
        Var<AbbreviationNode> node = new Var<AbbreviationNode>();
        return Sequence(
                NonindentSpace(),
                NodeSequence(
                        '*', Label(), push(node.setAndGet(new AbbreviationNode(popAsNode()))),
                        Sp(), ':', Sp(), AbbreviationText(node),
                        abbreviations.add(node.get())
                )
        );
    }

    public Rule AbbreviationText(Var<AbbreviationNode> node) {
        return Sequence(
                NodeSequence(
                        push(new SuperNode()),
                        ZeroOrMore(NotNewline(), Inline(), addAsChild())
                ),
                node.get().setExpansion(popAsNode())
        );
    }

    //*************** TOC *****************

    public Rule Toc() {
        return Sequence(
                NonindentSpace(),
                NodeSequence("[TOC",
                        Optional(Sp(), "level=", Digit(), push(match())),
                        "]",
                        push(new TocNode(headers, peek() instanceof String ? Integer.parseInt(popAsString()) : 6))
                )
        );
    }

    //************* TABLES ****************

    public Rule Table() {
        Var<TableNode> node = new Var<TableNode>();
        return NodeSequence(
                push(node.setAndGet(new TableNode())),
                Optional(
                        NodeSequence(
                                TableRow(), push(1, new TableHeaderNode()) && addAsChild(),
                                ZeroOrMore(TableRow(), addAsChild())
                        ),
                        addAsChild() // add the TableHeaderNode to the TableNode
                ),
                TableDivider(node),
                Optional(
                        NodeSequence(
                                TableRowAfterDivider(), push(1, new TableBodyNode()) && addAsChild(),
                                ZeroOrMore(TableRowAfterDivider(), addAsChild())
                        ),
                        addAsChild() // add the TableHeaderNode to the TableNode
                ),
                // only accept as table if we have at least one header or at least one body
                Optional(TableCaption(), addAsChild()),
                !node.get().getChildren().isEmpty()

        );
    }

    public Rule TableCaption() {
        return Sequence(
                '[',
                (ext(FOOTNOTES) ? TestNot('^') : EMPTY),
                Sp(),
                CaptionStart(),
                Optional(Sp(), Optional(']'), Sp()),
                Newline()
        );
    }

    public Rule CaptionStart() {
        return NodeSequence(
                push(new TableCaptionNode()),
                OneOrMore(CaptionInline(), addAsChild())
        );
    }

    public Rule CaptionInline() {
        return Sequence(
                TestNot(Newline()),
                TestNot(Sp(), Optional(']'), Sp(), Newline()),
                Inline()
        );
    }

    public Rule TableDivider(Var<TableNode> tableNode) {
        Var<Boolean> pipeSeen = new Var<Boolean>(Boolean.FALSE);
        return Sequence(
                Optional('|', pipeSeen.set(Boolean.TRUE)),
                OneOrMore(TableColumn(tableNode, pipeSeen)),
                pipeSeen.get() || tableNode.get().hasTwoOrMoreDividers(),
                Sp(), Newline()
        );
    }

    // vsch: TableColumnNode was not setting source range
    public Rule TableColumn(Var<TableNode> tableNode, Var<Boolean> pipeSeen) {
        Var<TableColumnNode> node = new Var<TableColumnNode>(new TableColumnNode());
        return Sequence(
                NodeSequence(
                        push(node.get()),
                        Sp(),
                        Optional(':', node.get().markLeftAligned()),
                        Sp(), OneOrMore('-'), Sp(),
                        Optional(':', node.get().markRightAligned()),
                        Sp(),
                        Optional('|', pipeSeen.set(Boolean.TRUE))
                ),
                tableNode.get().addColumn((TableColumnNode) pop())
        );
    }

    public Rule TableRow() {
        Var<Boolean> leadingPipe = new Var<Boolean>(Boolean.FALSE);
        return NodeSequence(
                push(new TableRowNode()),
                Optional('|', leadingPipe.set(Boolean.TRUE)),
                OneOrMore(TableCell(), addAsChild()),
                leadingPipe.get() || ((Node) peek()).getChildren().size() > 1 ||
                        getContext().getInputBuffer().charAt(matchEnd() - 1) == '|',
                Sp(), Newline()
        );
    }

    public Rule TableRowAfterDivider() {
        Var<Boolean> leadingPipe = new Var<Boolean>(Boolean.FALSE);
        return NodeSequence(
                push(new TableRowNode()),
                Optional('|', leadingPipe.set(Boolean.TRUE)),
                OneOrMore(TableCellAfterDivider(), addAsChild()),
                leadingPipe.get() || ((Node) peek()).getChildren().size() > 1 ||
                        getContext().getInputBuffer().charAt(matchEnd() - 1) == '|',
                Sp(), Newline()
        );
    }

    // vsch: #183 Exclude the trailing || from TableCellNode node, leading ones are not included, it makes it more intuitive
    // that the TableCell will include only the text of the cell.
    public Rule TableCell() {
        return Sequence(
                NodeSequence(
                        push(new TableCellNode()),
                        TestNot(Sp(), Optional(':'), Sp(), OneOrMore('-'), Sp(), Optional(':'), Sp(), FirstOf('|', Newline())),
                        Optional(Sp(), TestNot('|'), NotNewline()),
                        OneOrMore(
                                TestNot('|'), TestNot(Sp(), Newline()), Inline(),
                                addAsChild(),
                                Optional(Sp(), Test('|'), Test(Newline()))
                        )
                ),
                ZeroOrMore('|'),
                ((TableCellNode) peek()).setColSpan(Math.max(1, matchLength()))
        );
    }

    // vsch: if a table divider was seen then we can have cells that look like the divider cell, it is not a divider
    public Rule TableCellAfterDivider() {
        return Sequence(
                NodeSequence(
                        push(new TableCellNode()),
                        Optional(Sp(), TestNot('|'), NotNewline()),
                        OneOrMore(
                                TestNot('|'), TestNot(Sp(), Newline()), Inline(),
                                addAsChild(),
                                Optional(Sp(), Test('|'), Test(Newline()))
                        )
                ),
                ZeroOrMore('|'),
                ((TableCellNode) peek()).setColSpan(Math.max(1, matchLength()))
        );
    }

    //************* SMARTS ****************

    public Rule Smarts() {
        return NodeSequence(
                FirstOf(
                        Sequence(FirstOf("...", ". . ."), push(new SimpleNode(Type.Ellipsis))),
                        Sequence("---", push(new SimpleNode(Type.Emdash))),
                        Sequence("--", push(new SimpleNode(Type.Endash))),
                        Sequence('\'', push(new SimpleNode(Type.Apostrophe)))
                )
        );
    }

    //************* QUOTES ****************

    public Rule SingleQuoted() {
        return NodeSequence(
                !Character.isLetter(getContext().getInputBuffer().charAt(getContext().getCurrentIndex() - 1)),
                '\'',
                push(new QuotedNode(QuotedNode.Type.Single)),
                OneOrMore(TestNot(SingleQuoteEnd()), Inline(), addAsChild()),
                SingleQuoteEnd()
        );
    }

    public Rule SingleQuoteEnd() {
        return Sequence('\'', TestNot(Alphanumeric()));
    }

    public Rule DoubleQuoted() {
        return NodeSequence(
                '"',
                push(new QuotedNode(QuotedNode.Type.Double)),
                OneOrMore(TestNot('"'), Inline(), addAsChild()),
                '"'
        );
    }

    public Rule DoubleAngleQuoted() {
        return NodeSequence(
                "<<",
                push(new QuotedNode(QuotedNode.Type.DoubleAngle)),
                Optional(NodeSequence(Spacechar(), push(new SimpleNode(Type.Nbsp))), addAsChild()),
                OneOrMore(
                        FirstOf(
                                Sequence(NodeSequence(OneOrMore(Spacechar()), Test(">>"),
                                        push(new SimpleNode(Type.Nbsp))), addAsChild()),
                                Sequence(TestNot(">>"), Inline(), addAsChild())
                        )
                ),
                ">>"
        );
    }

    //************* HELPERS ****************

    public Rule NOrMore(char c, int n) {
        return Sequence(repeat(c, n), ZeroOrMore(c));
    }

    public Rule NodeSequence(Object... nodeRules) {
        return Sequence(
                push(getContext().getCurrentIndex()),
                Sequence(nodeRules),
                setIndices()
        );
    }

    public boolean setIndices() {
        AbstractNode node = (AbstractNode) peek();
        node.setStartIndex((Integer) pop(1));
        node.setEndIndex(currentIndex());
        return true;
    }

    public boolean addAsChild() {
        SuperNode parent = (SuperNode) peek(1);
        List<Node> children = parent.getChildren();
        Node child = popAsNode();
        if (child.getClass() == TextNode.class && !children.isEmpty()) {
            Node lastChild = children.get(children.size() - 1);
            if (lastChild.getClass() == TextNode.class) {
                // collapse peer TextNodes
                TextNode last = (TextNode) lastChild;
                TextNode current = (TextNode) child;
                last.append(current.getText());
                last.setEndIndex(current.getEndIndex());
                return true;
            }
        }
        children.add(child);
        return true;
    }

    public Node popAsNode() {
        return (Node) pop();
    }

    public String popAsString() {
        return (String) pop();
    }

    public boolean ext(int extension) {
        return (options & extension) > 0;
    }

    // called for inner parses for list items and blockquotes
    public RootNode parseInternal(StringBuilderVar block) {
        char[] chars = block.getChars();
        int[] ixMap = new int[chars.length + 1]; // map of cleaned indices to original indices

        // strip out CROSSED_OUT characters and build index map
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c != CROSSED_OUT) {
                ixMap[clean.length()] = i;
                clean.append(c);
            }
        }
        ixMap[clean.length()] = chars.length;

        // run inner parse
        char[] cleaned = new char[clean.length()];
        clean.getChars(0, cleaned.length, cleaned, 0);
        RootNode rootNode = parseInternal(cleaned);

        // correct AST indices with index map
        fixIndices(rootNode, ixMap);

        return rootNode;
    }

    protected void fixIndices(Node node, int[] ixMap) {
        ((AbstractNode) node).mapIndices(ixMap);
    }

    public RootNode parseInternal(char[] source) {
        ParsingResult<Node> result = parseToParsingResult(source);
        if (result.hasErrors()) {
            throw new RuntimeException("Internal error during markdown parsing:\n--- ParseErrors ---\n" +
                    printParseErrors(result)/* +
                    "\n--- ParseTree ---\n" +
                    printNodeTree(result)*/
            );
        }
        return (RootNode) result.resultValue;
    }

    ParsingResult<Node> parseToParsingResult(char[] source) {
        parsingStartTimeStamp = System.currentTimeMillis();
        return parseRunnerProvider.get(Root()).run(source);
    }

    protected boolean checkForParsingTimeout() {
        if (System.currentTimeMillis() - parsingStartTimeStamp > maxParsingTimeInMillis)
            throw new ParsingTimeoutException();
        return true;
    }

    protected interface SuperNodeCreator {
        SuperNode create(Node child);
    }

    protected interface SuperNodeTaskItemCreator extends SuperNodeCreator {
        SuperNode create(Node child, int taskType, String taskListMarker);
    }
}
