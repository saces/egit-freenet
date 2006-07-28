/*
 *    Copyright 2006 Shawn Pearce <spearce@spearce.org>
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
package org.spearce.jgit.lib;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.spearce.jgit.errors.CorruptObjectException;

public class PackReader
{
    private static final byte[] SIGNATURE = {'P', 'A', 'C', 'K'};

    private static final int IDX_HDR_LEN = 256 * 4;

    private final Repository repo;

    private final XInputStream packStream;

    private final XInputStream idxStream;

    private final long[] idxHeader;

    private long objectCnt;

    private int lastRead;

    public PackReader(final Repository parentRepo, final InputStream ps)
        throws IOException
    {
        repo = parentRepo;
        idxStream = null;
        idxHeader = null;

        packStream = new XInputStream(ps);
        try
        {
            readPackHeader();
        }
        catch (IOException ioe)
        {
            packStream.close();
            throw ioe;
        }
    }

    public PackReader(final Repository parentRepo, final File packFile)
        throws IOException
    {
        final String name = packFile.getName();
        final int dot = name.lastIndexOf('.');

        repo = parentRepo;
        packStream = new XInputStream(new FileInputStream(packFile));
        try
        {
            readPackHeader();
        }
        catch (IOException err)
        {
            packStream.close();
            throw err;
        }

        try
        {
            final File idxFile = new File(packFile.getParentFile(), name
                .substring(0, dot)
                + ".idx");
            if (idxFile.length() != (IDX_HDR_LEN + (24 * objectCnt) + (2 * Constants.OBJECT_ID_LENGTH)))
            {
                throw new CorruptObjectException("Pack index "
                    + idxFile.getName()
                    + " has incorrect file size.");
            }

            idxStream = new XInputStream(new FileInputStream(idxFile));
            idxHeader = new long[256];
            for (int k = 0; k < idxHeader.length; k++)
            {
                idxHeader[k] = idxStream.readUInt32();
            }
        }
        catch (IOException ioe)
        {
            packStream.close();
            throw ioe;
        }
    }

    public Iterator iterator()
    {
        try
        {
            packStream.position(SIGNATURE.length + 2 * 4);
        }
        catch (IOException ioe)
        {
            throw new RuntimeException("Can't iterate entries.", ioe);
        }

        return new Iterator()
        {
            private long current = 0;

            private PackedObjectReader last;

            public boolean hasNext()
            {
                return current < objectCnt;
            }

            public Object next()
            {
                if (!hasNext())
                {
                    throw new IllegalStateException("No more items.");
                }

                try
                {
                    // If the caller did not absorb the object data then we
                    // must do so now otherwise we will try to parse zipped
                    // data as though it were an object header.
                    //
                    if (last != null
                        && last.getDataOffset() == packStream.position())
                    {
                        final Inflater inf = new Inflater();
                        try
                        {
                            final byte[] input = new byte[1024];
                            final byte[] output = new byte[1024];
                            while (!inf.finished())
                            {
                                if (inf.needsInput())
                                {
                                    packStream.mark(input.length);
                                    lastRead = packStream.read(input);
                                    inf.setInput(input, 0, lastRead);
                                }
                                inf.inflate(output);
                            }
                            unread(-1, inf.getRemaining());
                        }
                        catch (DataFormatException dfe)
                        {
                            throw new RuntimeException("Cannot absorb packed"
                                + " object data as the pack is corrupt.", dfe);
                        }
                        finally
                        {
                            inf.end();
                        }
                    }

                    current++;
                    last = reader();
                    return last;
                }
                catch (IOException e)
                {
                    throw new RuntimeException("Can't read next pack entry.", e);
                }
            }

            public void remove()
            {
                throw new UnsupportedOperationException("Remove pack entry.");
            }
        };
    }

    public synchronized ObjectReader resolveBase(final ObjectId id)
        throws IOException
    {
        return repo.openObject(id);
    }

    public synchronized PackedObjectReader get(final ObjectId id)
        throws IOException
    {
        final long offset;
        final PackedObjectReader objReader;

        offset = findOffset(id);
        if (offset == -1)
        {
            return null;
        }

        packStream.position(offset);
        objReader = reader();
        objReader.setId(id);
        return objReader;
    }

    public synchronized void close() throws IOException
    {
        packStream.close();
        if (idxStream != null)
        {
            idxStream.close();
        }
    }

    synchronized int read(
        final long offset,
        final byte[] b,
        final int off,
        final int len) throws IOException
    {
        packStream.position(offset);
        packStream.mark(len);
        lastRead = packStream.read(b, off, len);
        return lastRead;
    }

    synchronized void unread(final long pos, final int len) throws IOException
    {
        if (pos == -1 || pos == packStream.position())
        {
            packStream.reset();
            packStream.skip(lastRead - len);
        }
    }

    private void readPackHeader() throws IOException
    {
        final byte[] sig;
        final long vers;

        sig = packStream.readFully(SIGNATURE.length);
        for (int k = 0; k < SIGNATURE.length; k++)
        {
            if (sig[k] != SIGNATURE[k])
            {
                throw new IOException("Not a PACK file.");
            }
        }

        vers = packStream.readUInt32();
        if (vers != 2 && vers != 3)
        {
            throw new IOException("Unsupported pack version " + vers + ".");
        }

        objectCnt = packStream.readUInt32();
    }

    private PackedObjectReader reader() throws IOException
    {
        final int typeCode;
        final String typeStr;
        ObjectId deltaBase = null;
        final long offset;
        int c;
        long size;
        int shift;

        c = packStream.readUInt8();
        typeCode = (c >> 4) & 7;
        size = c & 15;
        shift = 4;
        while ((c & 0x80) != 0)
        {
            c = packStream.readUInt8();
            size += (c & 0x7f) << shift;
            shift += 7;
        }

        switch (typeCode)
        {
        case Constants.OBJ_EXT:
            throw new IOException("Extended object types not supported.");
        case Constants.OBJ_COMMIT:
            typeStr = Constants.TYPE_COMMIT;
            break;
        case Constants.OBJ_TREE:
            typeStr = Constants.TYPE_TREE;
            break;
        case Constants.OBJ_BLOB:
            typeStr = Constants.TYPE_BLOB;
            break;
        case Constants.OBJ_TAG:
            typeStr = Constants.TYPE_TAG;
            break;
        case Constants.OBJ_TYPE_5:
            throw new IOException("Object type 5 not supported.");
        case Constants.OBJ_TYPE_6:
            throw new IOException("Object type 6 not supported.");
        case Constants.OBJ_DELTA:
            typeStr = null;
            break;
        default:
            throw new IOException("Unknown object type " + typeCode + ".");
        }

        if (typeCode == Constants.OBJ_DELTA)
        {
            deltaBase = new ObjectId(packStream
                .readFully(Constants.OBJECT_ID_LENGTH));
        }
        offset = packStream.position();
        return new PackedObjectReader(this, typeStr, size, offset, deltaBase);
    }

    private long findOffset(final ObjectId objId) throws IOException
    {
        final int levelOne = objId.getBytes()[0] & 0xff;
        long high;
        long low;

        if (idxHeader == null)
        {
            throw new IOException("Stream is not seekable.");
        }

        high = idxHeader[levelOne];
        low = levelOne == 0 ? 0 : idxHeader[levelOne - 1];
        do
        {
            final long mid = (low + high) / 2;
            final long offset;
            final int cmp;

            idxStream.position(IDX_HDR_LEN
                + ((4 + Constants.OBJECT_ID_LENGTH) * mid));
            offset = idxStream.readUInt32();
            cmp = objId.compareTo(idxStream
                .readFully(Constants.OBJECT_ID_LENGTH));
            if (cmp < 0)
            {
                high = mid;
            }
            else if (cmp == 0)
            {
                return offset;
            }
            else
            {
                low = mid + 1;
            }
        }
        while (low < high);
        return -1;
    }
}
