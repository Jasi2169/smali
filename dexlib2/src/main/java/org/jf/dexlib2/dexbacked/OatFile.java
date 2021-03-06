/*
 * Copyright 2014, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2.dexbacked;

import com.google.common.io.ByteStreams;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.OatFile.SymbolTable.Symbol;
import org.jf.dexlib2.dexbacked.raw.HeaderItem;
import org.jf.util.AbstractForwardSequentialList;

import javax.annotation.Nonnull;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;

public class OatFile extends BaseDexBuffer {
    private static final byte[] ELF_MAGIC = new byte[] { 0x7f, 'E', 'L', 'F' };
    private static final byte[] OAT_MAGIC = new byte[] { 'o', 'a', 't', '\n' };
    private static final int ELF_HEADER_SIZE = 52;

    // These are the "known working" versions that I have manually inspected the source for.
    // Later version may or may not work, depending on what changed.
    private static final int MIN_OAT_VERSION = 56;
    private static final int MAX_OAT_VERSION = 71;

    public static final int UNSUPPORTED = 0;
    public static final int SUPPORTED = 1;
    public static final int UNKNOWN = 2;

    @Nonnull private final OatHeader oatHeader;
    @Nonnull private final Opcodes opcodes;

    public OatFile(@Nonnull byte[] buf) {
        super(buf);

        if (buf.length < ELF_HEADER_SIZE) {
            throw new NotAnOatFileException();
        }

        verifyMagic(buf);

        OatHeader oatHeader = null;
        SymbolTable symbolTable = getSymbolTable();
        for (Symbol symbol: symbolTable.getSymbols()) {
            if (symbol.getName().equals("oatdata")) {
                oatHeader = new OatHeader(symbol.getFileOffset());
                break;
            }
        }

        if (oatHeader == null) {
            throw new InvalidOatFileException("Oat file has no oatdata symbol");
        }
        this.oatHeader = oatHeader;

        if (!oatHeader.isValid()) {
            throw new InvalidOatFileException("Invalid oat magic value");
        }

        this.opcodes = Opcodes.forArtVersion(oatHeader.getVersion());
    }

    private static void verifyMagic(byte[] buf) {
        for (int i = 0; i < ELF_MAGIC.length; i++) {
            if (buf[i] != ELF_MAGIC[i]) {
                throw new NotAnOatFileException();
            }
        }
    }

    public static OatFile fromInputStream(@Nonnull InputStream is)
            throws IOException {
        if (!is.markSupported()) {
            throw new IllegalArgumentException("InputStream must support mark");
        }
        is.mark(4);
        byte[] partialHeader = new byte[4];
        try {
            ByteStreams.readFully(is, partialHeader);
        } catch (EOFException ex) {
            throw new NotAnOatFileException();
        } finally {
            is.reset();
        }

        verifyMagic(partialHeader);

        is.reset();

        byte[] buf = ByteStreams.toByteArray(is);
        return new OatFile(buf);
    }

    public int getOatVersion() {
        return oatHeader.getVersion();
    }

    public int isSupportedVersion() {
        int version = getOatVersion();
        if (version < MIN_OAT_VERSION) {
            return UNSUPPORTED;
        }
        if (version <= MAX_OAT_VERSION) {
            return SUPPORTED;
        }
        return UNKNOWN;
    }

    @Nonnull
    public List<OatDexFile> getDexFiles() {
        return new AbstractForwardSequentialList<OatDexFile>() {
            @Override public int size() {
                return oatHeader.getDexFileCount();
            }

            @Nonnull @Override public Iterator<OatDexFile> iterator() {
                return new Iterator<OatDexFile>() {
                    int index = 0;
                    int offset = oatHeader.getDexListStart();

                    @Override public boolean hasNext() {
                        return index < size();
                    }

                    @Override public OatDexFile next() {
                        int filenameLength = readSmallUint(offset);
                        offset += 4;

                        // TODO: what is the correct character encoding?
                        String filename = new String(buf, offset, filenameLength, Charset.forName("US-ASCII"));
                        offset += filenameLength;

                        offset += 4; // checksum

                        int dexOffset = readSmallUint(offset) + oatHeader.offset;
                        offset += 4;

                        int classCount = readSmallUint(dexOffset + HeaderItem.CLASS_COUNT_OFFSET);
                        offset += 4 * classCount;

                        index++;

                        return new OatDexFile(dexOffset, filename);
                    }

                    @Override public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    public class OatDexFile extends DexBackedDexFile {
        @Nonnull public final String filename;

        public OatDexFile(int offset, @Nonnull String filename) {
            super(opcodes, OatFile.this.buf, offset);
            this.filename = filename;
        }

        public int getOatVersion() {
            return OatFile.this.getOatVersion();
        }

        @Override public boolean hasOdexOpcodes() {
            return true;
        }
    }

    private class OatHeader {
        private final int offset;

        public OatHeader(int offset) {
            this.offset = offset;
        }

        public boolean isValid() {
            for (int i=0; i<OAT_MAGIC.length; i++) {
                if (buf[offset + i] != OAT_MAGIC[i]) {
                    return false;
                }
            }

            for (int i=4; i<7; i++) {
                if (buf[offset + i] < '0' || buf[offset + i] > '9') {
                    return false;
                }
            }

            return buf[offset + 7] == 0;
        }

        public int getVersion() {
            return Integer.valueOf(new String(buf, offset + 4, 3));
        }

        public int getDexFileCount() {
            return readSmallUint(offset + 20);
        }

        public int getKeyValueStoreSize() {
            int version = getVersion();
            if (version < 56) {
                throw new IllegalStateException("Unsupported oat version");
            }
            int fieldOffset = 17 * 4;
            return readSmallUint(offset + fieldOffset);
        }

        public int getHeaderSize() {
            int version = getVersion();
             if (version >= 56) {
                return 18*4 + getKeyValueStoreSize();
            } else {
                throw new IllegalStateException("Unsupported oat version");
            }

        }

        public int getDexListStart() {
            return offset + getHeaderSize();
        }
    }

    @Nonnull
    private List<SectionHeader> getSections() {
        final int offset = readSmallUint(32);
        final int entrySize = readUshort(46);
        final int entryCount = readUshort(48);

        if (offset + (entrySize * entryCount) > buf.length) {
            throw new InvalidOatFileException("The ELF section headers extend past the end of the file");
        }

        return new AbstractList<SectionHeader>() {
            @Override public SectionHeader get(int index) {
                if (index < 0 || index >= entryCount) {
                    throw new IndexOutOfBoundsException();
                }
                return new SectionHeader(offset + (index * entrySize));
            }

            @Override public int size() {
                return entryCount;
            }
        };
    }

    @Nonnull
    private SymbolTable getSymbolTable() {
        for (SectionHeader header: getSections()) {
            if (header.getType() == SectionHeader.TYPE_DYNAMIC_SYMBOL_TABLE) {
                return new SymbolTable(header);
            }
        }
        throw new InvalidOatFileException("Oat file has no symbol table");
    }

    @Nonnull
    private StringTable getSectionNameStringTable() {
        int index = readUshort(50);
        if (index == 0) {
            throw new InvalidOatFileException("There is no section name string table");
        }

        try {
            return new StringTable(getSections().get(index));
        } catch (IndexOutOfBoundsException ex) {
            throw new InvalidOatFileException("The section index for the section name string table is invalid");
        }
    }

    private class SectionHeader {
        private final int offset;

        public static final int TYPE_DYNAMIC_SYMBOL_TABLE = 11;

        public SectionHeader(int offset) {
            this.offset = offset;
        }

        @Nonnull
        public String getName() {
            return getSectionNameStringTable().getString(readSmallUint(offset));
        }

        public int getType() {
            return readInt(offset + 4);
        }

        public long getAddress() {
            return readInt(offset + 12) & 0xFFFFFFFFL;
        }

        public int getOffset() {
            return readSmallUint(offset + 16);
        }

        public int getSize() {
            return readSmallUint(offset + 20);
        }

        public int getLink() {
            return readSmallUint(offset + 24);
        }

        public int getEntrySize() {
            return readSmallUint(offset + 36);
        }
    }

    class SymbolTable {
        @Nonnull private final StringTable stringTable;
        private final int offset;
        private final int entryCount;
        private final int entrySize;

        public SymbolTable(@Nonnull SectionHeader header) {
            try {
                this.stringTable = new StringTable(getSections().get(header.getLink()));
            } catch (IndexOutOfBoundsException ex) {
                throw new InvalidOatFileException("String table section index is invalid");
            }
            this.offset = header.getOffset();
            this.entrySize = header.getEntrySize();
            this.entryCount = header.getSize() / entrySize;

            if (offset + entryCount * entrySize > buf.length) {
                throw new InvalidOatFileException("Symbol table extends past end of file");
            }
        }

        @Nonnull
        public List<Symbol> getSymbols() {
            return new AbstractList<Symbol>() {
                @Override public Symbol get(int index) {
                    if (index < 0 || index >= entryCount) {
                        throw new IndexOutOfBoundsException();
                    }
                    return new Symbol(offset + index * entrySize);
                }

                @Override public int size() {
                    return entryCount;
                }
            };
        }

        public class Symbol {
            private final int offset;

            public Symbol(int offset) {
                this.offset = offset;
            }

            @Nonnull
            public String getName() {
                return stringTable.getString(readSmallUint(offset));
            }

            public int getValue() {
                return readSmallUint(offset + 4);
            }

            public int getSize() {
                return readSmallUint(offset + 8);
            }

            public int getSectionIndex() {
                return readUshort(offset + 14);
            }

            public int getFileOffset() {
                SectionHeader sectionHeader;
                try {
                    sectionHeader = getSections().get(getSectionIndex());
                } catch (IndexOutOfBoundsException ex) {
                    throw new InvalidOatFileException("Section index for symbol is out of bounds");
                }

                long sectionAddress = sectionHeader.getAddress();
                int sectionOffset = sectionHeader.getOffset();
                int sectionSize = sectionHeader.getSize();

                long symbolAddress = getValue();

                if (symbolAddress < sectionAddress || symbolAddress >= sectionAddress + sectionSize) {
                    throw new InvalidOatFileException("symbol address lies outside it's associated section");
                }

                long fileOffset = (sectionOffset + (getValue() - sectionAddress));
                assert fileOffset <= Integer.MAX_VALUE;
                return (int)fileOffset;
            }
        }
    }

    private class StringTable {
        private final int offset;
        private final int size;

        public StringTable(@Nonnull SectionHeader header) {
            this.offset = header.getOffset();
            this.size = header.getSize();

            if (offset + size > buf.length) {
                throw new InvalidOatFileException("String table extends past end of file");
            }
        }

        @Nonnull
        public String getString(int index) {
            if (index >= size) {
                throw new InvalidOatFileException("String index is out of bounds");
            }

            int start = offset + index;
            int end = start;
            while (buf[end] != 0) {
                end++;
                if (end >= offset + size) {
                    throw new InvalidOatFileException("String extends past end of string table");
                }
            }

            return new String(buf, start, end-start, Charset.forName("US-ASCII"));
        }

    }

    public static class InvalidOatFileException extends RuntimeException {
        public InvalidOatFileException(String message) {
            super(message);
        }
    }

    public static class NotAnOatFileException extends RuntimeException {
        public NotAnOatFileException() {}
    }
}