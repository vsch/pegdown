package org.pegdown;

/**
 * org.pegdown.TestMain
 *
 * @author chao
 * @version 1.0 - 2015-11-05
 */
public class TestMain {
    public static void main(String[] args) {
        String input = "[TOC]\n" +
                "\n" +
                "[TOC level=2]\n" +
                "\n" +
                "## ONE\n" +
                "\n" +
                "### ONE - I\n" +
                "\n" +
                "### ONE - II\n" +
                "\n" +
                "#### ONE - II - 1\n" +
                "\n" +
                "### ONE - III\n" +
                "\n" +
                "## TWO\n" +
                "\n" +
                "#### TWO - I - 1\n" +
                "\n" +
                "## THREE\n" +
                "\n" +
                "### THREE - I\n" +
                "\n" +
                "#### THREE - I - 1\n" +
                "\n" +
                "## FOUR";

        PegDownProcessor processor = new PegDownProcessor(Extensions.TOC);
        String html = processor.markdownToHtml(input);

        System.out.println(html);
    }
}
