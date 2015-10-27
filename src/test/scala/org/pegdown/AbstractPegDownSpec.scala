package org.pegdown

import java.io.{StringWriter, StringReader}
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.w3c.tidy.Tidy
import org.parboiled.common.FileUtils
import org.parboiled.support.ToStringFormatter
import org.parboiled.trees.GraphUtils
import ast.Node


abstract class AbstractPegDownSpec extends Specification {

  def test(testName: String)(implicit processor: PegDownProcessor): MatchResult[String] = {
    implicit val htmlSerializer = new ToHtmlSerializer(new LinkRenderer)
    testWithSerializer(testName)
  }

  def testAlt(testName: String, expectedNameSuffix : String)(implicit processor: PegDownProcessor): MatchResult[String] = {
    implicit val htmlSerializer = new ToHtmlSerializer(new LinkRenderer)
    testWithSerializer(testName, testName + expectedNameSuffix)
  }

  def test(testName: String, expectedOutput: String, htmlSerializer: ToHtmlSerializer = null)
          (implicit processor: PegDownProcessor): MatchResult[String] = {
    val markdown = FileUtils.readAllCharsFromResource(testName + ".md")
    require(markdown != null, "Test '" + testName + "' not found")

    val astRoot = processor.parseMarkdown(markdown)

    val actualHtml = Option(htmlSerializer).getOrElse(new ToHtmlSerializer(new LinkRenderer)).toHtml(astRoot)

    // debugging I: check the parse tree
    //assertEquals(printNodeTree(getProcessor().parser.parseToParsingResult(markdown)), "<parse tree>");

    // debugging II: check the AST
    // GraphUtils.printTree(astRoot, new ToStringFormatter[Node]) === ""

    // debugging III: check the actual (untidied) HTML
    // actualHtml === ""

    // tidy up html for fair equality test
    val tidyHtml = tidy(actualHtml)
    normalize(tidyHtml) === normalize(expectedOutput)
  }

  def testWithSerializer(testName: String)(implicit processor: PegDownProcessor, htmlSerializer: ToHtmlSerializer) = {
    val expectedUntidy = FileUtils.readAllTextFromResource(testName + ".html")
    require(expectedUntidy != null, "Test '" + testName + "' not found")
    test(testName, tidy(expectedUntidy), htmlSerializer)
  }

  def testWithSerializer(testName: String, expectedName : String)(implicit processor: PegDownProcessor, htmlSerializer: ToHtmlSerializer) = {
    val expectedUntidy = FileUtils.readAllTextFromResource(expectedName + ".html")
    require(expectedUntidy != null, "Test '" + testName + "' not found")
    test(testName, tidy(expectedUntidy), htmlSerializer)
  }

  def testAST(testName: String)(implicit processor: PegDownProcessor) = {
    testASTAlt(testName, "");
  }

  def testASTAlt(testName: String, expectedASTNameSuffix : String)(implicit processor: PegDownProcessor) = {
    val markdown = FileUtils.readAllCharsFromResource(testName + ".md")
    require(markdown != null, "Test '" + testName + "' not found")

    val expectedAst = FileUtils.readAllTextFromResource(testName + expectedASTNameSuffix + ".ast")
    require(expectedAst != null, "Expected AST for '" + testName + expectedASTNameSuffix + "' not found")

    val astRoot = processor.parseMarkdown(markdown)

    // check parse tree
    //assertEquals(printNodeTree(getProcessor().parser.parseToParsingResult(markdown)), "<parse tree>");

    normalize(GraphUtils.printTree(astRoot, new ToStringFormatter[Node]())) === normalize(expectedAst)
  }

  def tidy(html: String) = {
    val in = new StringReader(html)
    val out = new StringWriter
    val t = new Tidy
    t.setTabsize(4)
    t.setPrintBodyOnly(true)
    t.setShowWarnings(false)
    t.setQuiet(true)
    t.parse(in, out)
    out.toString
  }

  // vsch: seems like there is a bug in Tidy, passing in HTML with <br>\n results in <br>\n\n, and passing one with <br>\n\n results in <br>\n
  // didn't look too deep into it but the following for now solves the problem.
  def normalize(string: String) = string.replace("\r\n", "\n").replace("\r", "\n").replace("<br>\n\n", "<br>\n")

}
