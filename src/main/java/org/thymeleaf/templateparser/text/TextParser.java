/*
 * =============================================================================
 * 
 *   Copyright (c) 2011-2014, The THYMELEAF team (http://www.thymeleaf.org)
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 * =============================================================================
 */
package org.thymeleaf.templateparser.text;

import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;


/*
 * The TextParser is very similar in concept and structure to AttoParser's MarkupParser, but hugely simplified, given
 * text parsing does not need most of the events, configurability and conditions of markup parsing.
 *
 * Note that, instead of using AttoParser's IMarkupParser interface, the much simpler ITextHandler is used here instead.
 *
 * @author Daniel Fernandez
 * @since 3.0.0
 * 
 */
final class TextParser {



    private final BufferPool pool;
    private final boolean processComments;
    private final String standardDialectPrefix;







    TextParser(final int poolSize, final int bufferSize, final boolean processComments, final String standardDialectPrefix) {
        super();
        this.pool = new BufferPool(poolSize, bufferSize);
        this.processComments = processComments;
        this.standardDialectPrefix = standardDialectPrefix;
    }






    public void parse(final String document, final ITextHandler handler)
            throws TextParseException {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }
        parse(new StringReader(document), handler);
    }




    public void parse(
            final Reader reader, final ITextHandler handler)
            throws TextParseException {

        if (reader == null) {
            throw new IllegalArgumentException("Reader cannot be null");
        }

        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }

        ITextHandler handlerChain = handler;

        // The TextEventProcessorHandler will basically be in charge of controlling the stack of elements (the correct
        // nesting of element events).
        handlerChain = new EventProcessorTextHandler(handlerChain);

        // Just before the event processor kicks in, we need to scan the TEXT events searching for inlined
        // expressions such as [[${someVar}]] and convert them to element events, as if they were expressed in
        // a more verbose [# th:text="${someVar}"/] way.
        handlerChain = new InlinedExpressionProcessorTextHandler(handlerChain, this.standardDialectPrefix);

        // If comment processing is active (for JAVASCRIPT and CSS template modes), we need to look inside comments and
        // check if they are only wrapping elements or inlined expressions, in which case we will need to unwrap them.
        if (this.processComments) {
            handlerChain = new CommentProcessorTextHandler(handlerChain);
        }

        parseDocument(reader, this.pool.poolBufferSize, handlerChain);

    }





    /*
     * This method receiving the buffer size with package visibility allows
     * testing different buffer sizes.
     */
    void parseDocument(final Reader reader, final int suggestedBufferSize, final ITextHandler handler)
            throws TextParseException {


        final long parsingStartTimeNanos = System.nanoTime();

        char[] buffer = null;

        try {

            final TextParseStatus status = new TextParseStatus();

            handler.handleDocumentStart(parsingStartTimeNanos, 1, 1);

            int bufferSize = suggestedBufferSize;
            buffer = this.pool.allocateBuffer(bufferSize);

            int bufferContentSize = reader.read(buffer);

            boolean cont = (bufferContentSize != -1);

            status.offset = -1;
            status.line = 1;
            status.col = 1;
            status.inStructure = false;

            while (cont) {

                parseBuffer(buffer, 0, bufferContentSize, handler, status);

                int readOffset = 0;
                int readLen = bufferSize;

                if (status.offset == 0) {

                    if (bufferContentSize == bufferSize) {
                        // Buffer is not big enough, double it!

                        char[] newBuffer = null;
                        try {

                            bufferSize *= 2;

                            newBuffer = this.pool.allocateBuffer(bufferSize);
                            System.arraycopy(buffer, 0, newBuffer, 0, bufferContentSize);

                            this.pool.releaseBuffer(buffer);

                            buffer = newBuffer;

                        } catch (final Exception ignored) {
                            this.pool.releaseBuffer(newBuffer);
                        }

                    }

                    // it's possible for two reads to occur in a row and 1) read less than the bufferSize and 2)
                    // still not find the next tag/end of structure
                    readOffset = bufferContentSize;
                    readLen = bufferSize - readOffset;

                } else if (status.offset < bufferContentSize) {

                    System.arraycopy(buffer, status.offset, buffer, 0, bufferContentSize - status.offset);

                    readOffset = bufferContentSize - status.offset;
                    readLen = bufferSize - readOffset;

                    status.offset = 0;
                    bufferContentSize = readOffset;

                }

                final int read = reader.read(buffer, readOffset, readLen);
                if (read != -1) {
                    bufferContentSize = readOffset + read;
                } else {
                    cont = false;
                }

            }

            // Iteration done, now it's time to clean up in case we still have some text to be notified

            int lastLine = status.line;
            int lastCol = status.col;

            final int lastStart = status.offset;
            final int lastLen = bufferContentSize - lastStart;

            if (lastLen > 0) {

                if (status.inStructure) {
                    throw new TextParseException(
                            "Incomplete structure: \"" + new String(buffer, lastStart, lastLen) + "\"", status.line, status.col);
                }

                handler.handleText(buffer, lastStart, lastLen, status.line, status.col);

                // As we have produced an additional text event, we need to fast-forward the
                // lastLine and lastCol position to include the last text structure.
                for (int i = lastStart; i < (lastStart + lastLen); i++) {
                    final char c = buffer[i];
                    if (c == '\n') {
                        lastLine++;
                        lastCol = 1;
                    } else {
                        lastCol++;
                    }

                }

            }

            final long parsingEndTimeNanos = System.nanoTime();
            handler.handleDocumentEnd(parsingEndTimeNanos, (parsingEndTimeNanos - parsingStartTimeNanos), lastLine, lastCol);

        } catch (final TextParseException e) {
            throw e;
        } catch (final Exception e) {
            throw new TextParseException(e);
        } finally {
            this.pool.releaseBuffer(buffer);
            try {
                reader.close();
            } catch (final Throwable ignored) {
                // This exception can be safely ignored
            }
        }

    }








    private void parseBuffer(
            final char[] buffer, final int offset, final int len,
            final ITextHandler handler, final TextParseStatus status)
            throws TextParseException {


        final int[] locator = new int[] {status.line, status.col};

        int currentLine;
        int currentCol;

        final int maxi = offset + len;
        int i = offset;
        int current = i;

        boolean inStructure;

        boolean inOpenElement = false;
        boolean inCloseElement = false;
        boolean inComment = false;

        int tagStart;
        int tagEnd;

        while (i < maxi) {

            currentLine = locator[0];
            currentCol = locator[1];

            inStructure = (inOpenElement || inCloseElement || inComment);

            if (!inStructure) {

                tagStart = TextParsingUtil.findNextStructureStart(buffer, i, maxi, locator);

                if (tagStart == -1) {

                    status.offset = current;
                    status.line = currentLine;
                    status.col = currentCol;
                    status.inStructure = false;
                    return;

                }

                inOpenElement = TextParsingElementUtil.isOpenElementStart(buffer, tagStart, maxi);
                if (!inOpenElement) {
                    inCloseElement = TextParsingElementUtil.isCloseElementStart(buffer, tagStart, maxi);
                    if (!inCloseElement && this.processComments) {
                        inComment = TextParsingCommentUtil.isCommentStart(buffer, tagStart, maxi);
                    }
                }

                inStructure = (inOpenElement || inCloseElement || inComment);

                while (!inStructure) {
                    // We found a '[' or a '/', but it cannot be considered beginning of any known structure

                    ParsingLocatorUtil.countChar(locator, buffer[tagStart]);
                    tagStart = TextParsingUtil.findNextStructureStart(buffer, tagStart + 1, maxi, locator);

                    if (tagStart == -1) {
                        status.offset = current;
                        status.line = currentLine;
                        status.col = currentCol;
                        status.inStructure = false;
                        return;
                    }

                    inOpenElement = TextParsingElementUtil.isOpenElementStart(buffer, tagStart, maxi);
                    if (!inOpenElement) {
                        inCloseElement = TextParsingElementUtil.isCloseElementStart(buffer, tagStart, maxi);
                        if (!inCloseElement && this.processComments) {
                            inComment = TextParsingCommentUtil.isCommentStart(buffer, tagStart, maxi);
                        }
                    }

                    inStructure = (inOpenElement || inCloseElement || inComment);

                }


                if (tagStart > current) {
                    // We avoid empty-string text events

                    handler.handleText(
                            buffer, current, (tagStart - current),
                            currentLine, currentCol);

                }

                current = tagStart;
                i = current;

            } else {


                tagEnd =
                        inComment? TextParsingUtil.findNextCommentEnd(buffer, i, maxi, locator) :
                                   TextParsingUtil.findNextStructureEndAvoidQuotes(buffer, i, maxi, locator);

                if (tagEnd < 0) {
                    // This is an unfinished structure
                    status.offset = current;
                    status.line = currentLine;
                    status.col = currentCol;
                    status.inStructure = true;
                    return;
                }

                if (inOpenElement) {
                    // This is a open/standalone tag (to be determined by looking at the antepenultimate character)

                    if ((buffer[tagEnd - 1] == '/')) {
                        TextParsingElementUtil.
                                parseStandaloneElement(buffer, current, (tagEnd - current) + 1, currentLine, currentCol, handler);
                    } else {
                        TextParsingElementUtil.
                                parseOpenElement(buffer, current, (tagEnd - current) + 1, currentLine, currentCol, handler);
                    }

                    inOpenElement = false;

                } else if (inCloseElement) {
                    // This is a closing tag

                    TextParsingElementUtil.
                            parseCloseElement(buffer, current, (tagEnd - current) + 1, currentLine, currentCol, handler);

                    inCloseElement = false;

                } else if (inComment) {
                    // This is a comment! (obviously ;-))

                    TextParsingCommentUtil.parseComment(buffer, current, (tagEnd - current) + 1, currentLine, currentCol, handler);

                    inComment = false;

                } else {

                    throw new IllegalStateException("Illegal parsing state: structure is not of a recognized type");

                }

                // The ']' char will be considered as processed too
                ParsingLocatorUtil.countChar(locator, buffer[tagEnd]);

                current = tagEnd + 1;
                i = current;

            }

        }

        status.offset = current;
        status.line = locator[0];
        status.col = locator[1];
        status.inStructure = false;

    }



    




    /*
     * This class models a pool of buffers, used to keep the amount of
     * large char[] buffer objects required to operate to a minimum.
     *
     * Note this pool never blocks, so if a new buffer is needed and all
     * are currently allocated, a new char[] object is created and returned.
     *
     */
    private static final class BufferPool {

        private final char[][] pool;
        private final boolean[] allocated;
        private final int poolBufferSize;

        private BufferPool(final int poolSize, final int poolBufferSize) {

            super();

            this.pool = new char[poolSize][];
            this.allocated = new boolean[poolSize];
            this.poolBufferSize = poolBufferSize;

            for (int i = 0; i < this.pool.length; i++) {
                this.pool[i] = new char[this.poolBufferSize];
            }
            Arrays.fill(this.allocated, false);

        }

        private synchronized char[] allocateBuffer(final int bufferSize) {
            if (bufferSize != this.poolBufferSize) {
                // We will only pool buffers of the default size. If a different size is required, we just
                // create it without pooling.
                return new char[bufferSize];
            }
            for (int i = 0; i < this.pool.length; i++) {
                if (!this.allocated[i]) {
                    this.allocated[i] = true;
                    return this.pool[i];
                }
            }
            return new char[bufferSize];
        }

        private synchronized void releaseBuffer(final char[] buffer) {
            if (buffer == null) {
                return;
            }
            if (buffer.length != this.poolBufferSize) {
                // This buffer cannot be part of the pool - only buffers with a specific size are contained
                return;
            }
            for (int i = 0; i < this.pool.length; i++) {
                if (this.pool[i] == buffer) {
                    // Found it. Mark it as non-allocated
                    this.allocated[i] = false;
                    return;
                }
            }
            // The buffer wasn't part of our pool. Just return.
        }


    }


}
