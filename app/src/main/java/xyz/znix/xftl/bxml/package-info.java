/**
 * BXML (binary XML) is a custom serialisation format for storing XML documents.
 * <p>
 * It's designed to take up less space than regular XML, and (more importantly)
 * be serialised and deserialised at very high speeds.
 * <p>
 * It's first use is for the Slipstream patched XML cache, though it's something
 * we can use whenever it's convenient.
 * <p>
 * BXML doesn't care about the whitespace in documents, so you can expect
 * roughly the same changes as running the document through an XML formatter.
 * <p>
 * <h2>Encoding</h2>
 * The format is very simple, as follows:
 * <p>
 * Every element is encoded with, in sequence, it's name (a string), the number
 * of attributes (a varint), the attributes (pairs of strings), then the content.
 * <p>
 * The content is a sequence of blocks, each one starting with a number to denote
 * its type:
 * <p>
 * 0: Text (a single string) <br/>
 * 1: An element (nesting this whole section) <br/>
 * 2: The end-of-content marker.
 * <p>
 * The root element begins at the start of the file.
 * <p>
 * A 'varint' is a way of encoding a positive integer. It's split into 7-bit
 * chunks, which are written (little-endian) in sequence. The 8th bit (MSB)
 * is set when there are more chunks remaining.
 * <p>
 * Strings are encoded first as an index varint, which is either zero for a literal
 * string, or the ID of a previous string. Each string gets an ID, starting at
 * one. This de-duplicates all the strings, which saves a lot of space.
 * <p>
 * If the index is zero to indicate a literal string, then the string is encoded
 * to UTF-8 and it's length in bytes is written as a varint, followed by the data.
 */
package xyz.znix.xftl.bxml;
