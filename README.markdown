Introduction
------------

_pegdown_ is a pure Java library for clean and lightweight [Markdown] processing based on a [parboiled] PEG parser.

_pegdown_ is nearly 100% compatible with the original Markdown specification and fully passes the original Markdown test suite.
On top of the standard Markdown feature set _pegdown_ implements a number of extensions similar to what other popular
Markdown processors offer. You can also extend _pegdown_ by your own plugins!
Currently _pegdown_ supports the following extensions over standard Markdown:

* `SMARTS`: Beautifies apostrophes, ellipses ("..." and ". . .") and dashes ("--" and "---")
* `QUOTES`: Beautifies single quotes, double quotes and double angle quotes (&laquo; and &raquo;)
* `SMARTYPANTS`: Convenience extension enabling both, SMARTS and QUOTES, at once.
* `ABBREVIATIONS`: Abbreviations in the way of [PHP Markdown Extra](http://michelf.com/projects/php-markdown/extra/#abbr).
* `ANCHORLINKS`: Generate anchor links for headers by taking the first range of alphanumerics and spaces.
* `HARDWRAPS`: Alternative handling of newlines, see [Github-flavoured-Markdown]
* `AUTOLINKS`: Plain (undelimited) autolinks the way [Github-flavoured-Markdown] implements them.
* `TABLES`: Tables similar to [MultiMarkdown] (which is in turn like the [PHP Markdown Extra](http://michelf.com/projects/php-markdown/extra/#table) tables, but with colspan support).
* `DEFINITION_LISTS`: Definition lists in the way of [PHP Markdown Extra](http://michelf.com/projects/php-markdown/extra/#def-list).
* `FENCED_CODE_BLOCKS`: Fenced Code Blocks in the way of [PHP Markdown Extra](http://michelf.com/projects/php-markdown/extra/#fenced-code-blocks) or [Github-flavoured-Markdown].
* `SUPPRESS_HTML_BLOCKS`: Suppresses the output of HTML blocks.
* `SUPPRESS_INLINE_HTML`: Suppresses the output of inline HTML elements.
* `WIKILINKS`: Support `[[Wiki-style links]]` with a customizable URL rendering logic.
* `STRIKETHROUGH`: Support ~~strikethroughs~~ as supported in Pandoc and Github.
* `ATXHEADERSPACE`: Require a space between the `#` and the header title text, as per [Github-flavoured-Markdown]. Frees up `#` without a space to be just plain text.
* `FORCELISTITEMPARA`: Wrap a list item or definition term in `<p>` tags if it contains more than a simple paragraph.
* `RELAXEDHRULES`: allow horizontal rules without a blank line following them.
* `TASKLISTITEMS`: parses bullet lists of the form `* [ ]` and `* [x]` to create GitHub like task list items:
    * [ ] open task item
    * [x] closed or completed task item.
    * [X] also closed or completed task item.
* `EXTANCHORLINKS`: Generate anchor links for headers using complete contents of the header.
    * Spaces and non-alphanumerics replaced by `-`, multiple dashes trimmed to one.
    * Anchor link is added as first element inside the header with empty content: `<h1><a name="header"></a>header</h1>`
* `EXTANCHORLINKS_WRAP`: used in conjunction with above to create an anchor that wraps header content: `<h1><a name="header">header</a></h1>` 
* `TOC`: used to enable table of contents extension `[TOC]` or `[TOC level=n]` where n is 1..6, corresponding to max header level to include in the table of contents. This element must be the first one on a line to be recognized.
* `DUMMY_REFERENCE_KEY`: to make the parser use the `ReferenceNode.DUMMY_REFERENCE_KEY` instead of `null` for `RefLink` and `RefImage` `referenceKey` when markdown contains `[]` as the reference, as in `[...][]` and `![...][]`. Otherwise there is no way to tell the difference between `[ ][]` and `[ ]` or `![ ][]` and `![ ]` from the AST.
* `MULTI_LINE_IMAGE_URLS`: enables parsing of image urls spanning more than one line the format is strict `![alt text](urladdress?` must be the last non-blank segment on a line. The terminating `)` or `"title")` must be the first non-indented segment on the line. Everything in between is sucked up as part of the URL. EVERYTHING, including blank lines.
* `RELAXED_STRONG_EMPHASIS_RULES`: allows Strong/Emphasis marks to start when not preceded by alphanumeric instead of only when preceded by spaces
* `FOOTNOTES`: Support MultiMarkdown style footnotes: `[^n] for footnote reference` and `[^n]: Footnote text` for footnotes. Where `n` is one or more digit, letter, `-`, `_` or `.`. Footnotes will be put at the bottom of the page, sequentially numbered in order of appearance of the footnote reference. Footnotes that are not referenced will NOT be included in the HTML output.  

    ```markdown
    This paragraph has a footnote[^1] and another footnote[^two].
    
    This one has more but out of sequence[^4] and[^eight]. 
    
    [^two]: Footnote 2 with a bit more text
        and another continuation line
        
    [^1]: Footnote 1
    
    [^3]: Unused footnote, it will not be added to the end of the page.
    
    [^4]: Out of sequence footnote
    
    [^eight]: Have one that is used.
    ```                        
    
    will generate:
    
    ```html
    <p>This paragraph has a footnote<sup id="fnref-1"><a href="#fn-1" class="footnote-ref">1</a></sup> and another footnote<sup id="fnref-2"><a href="#fn-2" class="footnote-ref">2</a></sup>.</p>
    <p>This one has more but out of sequence<sup id="fnref-3"><a href="#fn-3" class="footnote-ref">3</a></sup> and<sup id="fnref-4"><a href="#fn-4" class="footnote-ref">4</a></sup>. </p>
    <div class="footnotes">
    <hr/>
    <ol>
    <li id="fn-1"><p>Footnote 1<a href="#fnref-1" class="footnote-backref">&#8617;</a></p></li>
    <li id="fn-2"><p>Footnote 2 with a bit more text  and another continuation line<a href="#fnref-2" class="footnote-backref">&#8617;</a></p></li>
    <li id="fn-3"><p>Out of sequence footnote<a href="#fnref-3" class="footnote-backref">&#8617;</a></p></li>
    <li id="fn-4"><p>Have one that is used.<a href="#fnref-4" class="footnote-backref">&#8617;</a></p></li>
    </ol>
    </div>
    ```
    to look like this:
    
    <p>This paragraph has a footnote<sup id="fnref-1"><a href="#fn-1" class="footnote-ref">1</a></sup> and another footnote<sup id="fnref-2"><a href="#fn-2" class="footnote-ref">2</a></sup>.</p>
    <p>This one has more but out of sequence<sup id="fnref-3"><a href="#fn-3" class="footnote-ref">3</a></sup> and<sup id="fnref-4"><a href="#fn-4" class="footnote-ref">4</a></sup>. </p>
    <div class="footnotes">
    <hr/>
    <ol>
    <li id="fn-1"><p>Footnote 1<a href="#fnref-1" class="footnote-backref">&#8617;</a></p></li>
    <li id="fn-2"><p>Footnote 2 with a bit more text  and another continuation line<a href="#fnref-2" class="footnote-backref">&#8617;</a></p></li>
    <li id="fn-3"><p>Out of sequence footnote<a href="#fnref-3" class="footnote-backref">&#8617;</a></p></li>
    <li id="fn-4"><p>Have one that is used.<a href="#fnref-4" class="footnote-backref">&#8617;</a></p></li>
    </ol>
    </div>
    
Note: _pegdown_ differs from the original Markdown in that it ignores in-word emphasis as in

> my_cool_file.txt  
> 2*3*4=5

Currently this "extension" cannot be switched off.


Installation
------------

You have two options:

* Download the JAR for the latest version from [here](http://repo1.maven.org/maven2/org/pegdown/pegdown/).
  _pegdown_ 1.6.0 has only one dependency: [parboiled for Java][parboiled], version 1.1.7.
   
* The pegdown artifact is also available from maven central with group id **org.pegdown** and artifact-id **pegdown**.


Usage
-----

Using _pegdown_ is very simple: Just create a new instance of a [PegDownProcessor] and call one of its
`markdownToHtml` methods to convert the given Markdown source to an HTML string. If you'd like to customize the
rendering of HTML links (Auto-Links, Explicit-Links, Mail-Links, Reference-Links and/or Wiki-Links), e.g. for adding
`rel="nofollow"` attributes based on some logic you can supply your own instance of a [LinkRenderer] with the call
to `markdownToHtml`.

You can also use pegdown only for the actual parsing of the Markdown source and do the serialization to the
target format (e.g. XML) yourself. To do this just call the `parseMarkdown` method of the [PegDownProcessor] to obtain
the root node of the Abstract Syntax Tree for the document.
With a custom [Visitor] implementation you can do whatever serialization you want. As an example you might want to
take a look at the [sources of the ToHtmlSerializer][ToHtmlSerializer].

Note that the first time you create a [PegDownProcessor] it can take up to a few hundred milliseconds to prepare the
underlying parboiled parser instance. However, once the first processor has been built all further instantiations will
be fast. Also, you can reuse an existing [PegDownProcessor] instance as often as you want, as long as you prevent
concurrent accesses, since neither the [PegDownProcessor] nor the underlying parser is thread-safe.

See <http://sirthias.github.com/pegdown/api> for the pegdown API documentation.


Plugins
-------

Since parsing and serialisation are two different things there are two different plugin mechanisms, one for the parser,
and one for the [ToHtmlSerializer]. Most plugins would probably implement both, but it is possible that a plugin might
just implement the parser plugin interface.

For the parser there are two plugin points, one for inline plugins (inside a paragraph) and one for block plugins. These
are provided to the parser using the [PegDownPlugins] class. For convenience of use this comes with its own builder.
You can either pass individual rules to this builder (which is what you probably would do if you were using Scala
rules), but you can also pass it a parboiled Java parser class which implements either [InlinePluginParser] or
[BlockPluginParser] or both. [PegDownPlugins] will enhance this parser for you, so as a user of a plugin you just need
to pass the class to it (and the arguments for that classes constructor, if any). To implement the plugin, you would
write a normal parboiled parser, and implement the appropriate parser plugin interface. You can extend the pegdown
parser, this is useful if you want to reuse any of its rules.

For the serializer there is [ToHtmlSerializerPlugin] interface. It is called when a node that the [ToHtmlSerializer]
doesn't know how to process is encountered (i.e. one produced by a parser plugin). Its `accept` method is passed the
node, the visitor (so if the node contains child nodes they can be rendered using the parent) and the printer for the
plugin to print to. The `accept` method returns true if it knew how to handle the node or false if otherwise and
the [ToHtmlSerializer] loops through each plugin breaking when it reaches one that returns true and if it finds none
throws an exception like it used to.

As an very simple example you might want to take a look at the [sources of the PluginParser test class][PluginParser].


Parsing Timeouts
----------------

Since Markdown has no official grammar and contains a number of ambiguities the parsing of Markdown source, especially
with enabled language extensions, can be "hard" and result, in certain corner cases, in exponential parsing time.
In order to provide a somewhat predictable behavior *pegdown* therefore supports the specification of a parsing timeout,
which you can supply to the [PegDownProcessor] constructor.

If the parser happens to run longer than the specified timeout period it terminates itself with an exception, which
causes the `markdownToHtml` method to return `null`. Your application should then deal with this case accordingly and,
for example, inform the user.

The default timeout, if not explicitly specified, is 2 seconds.


IDE Support
-----------

The excellent [idea-markdown plugin] for IntelliJ IDEA, RubyMine, PhpStorm, WebStorm, PyCharm and appCode uses _pegdown_
as its underlying parsing engine. The plugin gives you proper syntax-highlighting for markdown source and shows you
exactly, how _pegdown_ will parse your texts.


Credits
-------

A large part of the underlying PEG grammar was developed by John MacFarlane and made available with his
tool [peg-markdown](http://github.com/jgm/peg-markdown).   


License
-------

_pegdown_ is licensed under [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).


Patch Policy
------------

Feedback and contributions to the project, no matter what kind, are always very welcome.
However, patches can only be accepted from their original author.
Along with any patches, please state that the patch is your original work and that you license the work to the pegdown
project under the project’s open source license.
  
[Markdown]: http://daringfireball.net/projects/markdown/ "Main Markdown site"
[parboiled]: http://www.parboiled.org
[Github-flavoured-Markdown]: http://github.github.com/github-flavored-markdown/
[MultiMarkdown]: http://fletcherpenney.net/multimarkdown/users_guide/multimarkdown_syntax_guide/
[PegDownProcessor]: http://www.decodified.com/pegdown/api/org/pegdown/PegDownProcessor.html
[LinkRenderer]: http://www.decodified.com/pegdown/api/org/pegdown/LinkRenderer.html
[Visitor]: http://www.decodified.com/pegdown/api/org/pegdown/ast/Visitor.html
[ToHtmlSerializer]: https://github.com/sirthias/pegdown/blob/master/src/main/java/org/pegdown/ToHtmlSerializer.java
[idea-markdown plugin]: https://github.com/nicoulaj/idea-markdown
[SBT]: http://www.scala-sbt.org/
[Node]: http://www.decodified.com/pegdown/api/org/pegdown/ast/Node.html
[PegDownPlugins]: http://github.com/sirthias/pegdown/blob/master/src/main/java/org/pegdown/plugins/PegDownPlugins.java
[InlinePluginParser]: http://github.com/sirthias/pegdown/blob/master/src/main/java/org/pegdown/plugins/InlinePluginParser.java
[BlockPluginParser]: http://github.com/sirthias/pegdown/blob/master/src/main/java/org/pegdown/plugins/BlockPluginParser.java
[ToHtmlSerializerPlugin]: http://github.com/sirthias/pegdown/blob/master/src/main/java/org/pegdown/plugins/ToHtmlSerializerPlugin.java
[PluginParser]: http://github.com/sirthias/pegdown/blob/master/src/test/java/org/pegdown/PluginParser.java

