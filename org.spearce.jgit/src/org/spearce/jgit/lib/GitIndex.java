package org.spearce.jgit.lib;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;

import org.spearce.jgit.errors.CorruptObjectException;
import org.spearce.jgit.errors.NotSupportedException;
import org.spearce.jgit.util.FS;

/**
 * A representation of the Git index.
 *
 * The index points to the objects currently checked out or in the process of
 * being prepared for committing or objects involved in an unfinished merge.
 *
 * The abstract format is:<br/> path stage flags statdata SHA-1
 * <ul>
 * <li>Path is the relative path in the workdir</li>
 * <li>stage is 0 (normally), but when
 * merging 1 is the common ancestor version, 2 is 'our' version and 3 is 'their'
 * version. A fully resolved merge only contains stage 0.</li>
 * <li>flags is the object type and information of validity</li>
 * <li>statdata is the size of this object and some other file system specifics,
 * some of it ignored by JGit</li>
 * <li>SHA-1 represents the content of the references object</li>
 * </ul>
 *
 * An index can also contain a tree cache which we ignore for now. We drop the
 * tree cache when writing the index.
 */
public class GitIndex {

	/** Stage 0 represents merged entries. */
	public static final int STAGE_0 = 0;

	private RandomAccessFile cache;

	private File cacheFile;

	// Index is modified
	private boolean changed;

	// Stat information updated
	private boolean statDirty;

	private Header header;

	private long lastCacheTime;

	private final Repository db;

	private Map entries = new TreeMap(new Comparator() {
		public int compare(Object arg0, Object arg1) {
			byte[] a = (byte[]) arg0;
			byte[] b = (byte[]) arg1;
			for (int i = 0; i < a.length && i < b.length; ++i) {
				int c = a[i] - b[i];
				if (c != 0)
					return c;
			}
			if (a.length < b.length)
				return -1;
			else if (a.length > b.length)
				return 1;
			return 0;
		}
	});

	/**
	 * Construct a Git index representation.
	 * @param db
	 */
	public GitIndex(Repository db) {
		this.db = db;
		this.cacheFile = new File(db.getDirectory(), "index");
	}

	/**
	 * @return true if we have modified the index in memory since reading it from disk
	 */
	public boolean isChanged() {
		return changed || statDirty;
	}

	/**
	 * Reread index data from disk if the index file has been changed
	 * @throws IOException
	 */
	public void rereadIfNecessary() throws IOException {
		if (cacheFile.exists() && cacheFile.lastModified() != lastCacheTime) {
			read();
		}
	}

	/**
	 * Add the content of a file to the index.
	 *
	 * @param wd workdir
	 * @param f the file
	 * @return a new or updated index entry for the path represented by f
	 * @throws IOException
	 */
	public Entry add(File wd, File f) throws IOException {
		byte[] key = makeKey(wd, f);
		Entry e = (Entry) entries.get(key);
		if (e == null) {
			e = new Entry(key, f, 0);
			entries.put(key, e);
		} else {
			e.update(f);
		}
		return e;
	}

	/**
	 * Remove a path from the index.
	 *
	 * @param wd
	 *            workdir
	 * @param f
	 *            the file whose path shall be removed.
	 * @return true if such a path was found (and thus removed)
	 */
	public boolean remove(File wd, File f) {
		byte[] key = makeKey(wd, f);
		return entries.remove(key) != null;
	}

	/**
	 * Read the cache file into memory.
	 *
	 * @throws IOException
	 */
	public void read() throws IOException {
		long t0 = System.currentTimeMillis();
		changed = false;
		statDirty = false;
		if (!cacheFile.exists()) {
			header = null;
			entries.clear();
			lastCacheTime = 0;
			return;
		}
		cache = new RandomAccessFile(cacheFile, "r");
		try {
			FileChannel channel = cache.getChannel();
			ByteBuffer buffer = ByteBuffer.allocateDirect((int) cacheFile.length());
			buffer.order(ByteOrder.BIG_ENDIAN);
			int j = channel.read(buffer);
			if (j != buffer.capacity())
				throw new IOException("Could not read index in one go, only "+j+" out of "+buffer.capacity()+" read");
			buffer.flip();
			header = new Header(buffer);
			entries.clear();
			for (int i = 0; i < header.entries; ++i) {
				Entry entry = new Entry(buffer);
				entries.put(entry.name, entry);
			}
			long t1 = System.currentTimeMillis();
			lastCacheTime = cacheFile.lastModified();
			System.out.println("Read index "+cacheFile+" in "+((t1-t0)/1000.0)+"s");
		} finally {
			cache.close();
		}
	}

	/**
	 * Write content of index to disk.
	 *
	 * @throws IOException
	 */
	public void write() throws IOException {
		checkWriteOk();
		File tmpIndex = new File(cacheFile.getAbsoluteFile() + ".tmp");
		File lock = new File(cacheFile.getAbsoluteFile() + ".lock");
		if (!lock.createNewFile())
			throw new IOException("Index file is in use");
		try {
			FileOutputStream fileOutputStream = new FileOutputStream(tmpIndex);
			FileChannel fc = fileOutputStream.getChannel();
			ByteBuffer buf = ByteBuffer.allocate(4096);
			MessageDigest newMessageDigest = Constants.newMessageDigest();
			header = new Header(entries);
			header.write(buf);
			buf.flip();
			newMessageDigest
					.update(buf.array(), buf.arrayOffset(), buf.limit());
			fc.write(buf);
			buf.flip();
			buf.clear();
			for (Iterator i = entries.values().iterator(); i.hasNext();) {
				Entry e = (Entry) i.next();
				e.write(buf);
				buf.flip();
				newMessageDigest.update(buf.array(), buf.arrayOffset(), buf
						.limit());
				fc.write(buf);
				buf.flip();
				buf.clear();
			}
			buf.put(newMessageDigest.digest());
			buf.flip();
			fc.write(buf);
			fc.close();
			fileOutputStream.close();
			if (cacheFile.exists())
				if (!cacheFile.delete())
					throw new IOException(
						"Could not rename delete old index");
			if (!tmpIndex.renameTo(cacheFile))
				throw new IOException(
						"Could not rename temporary index file to index");
			changed = false;
			statDirty = false;
		} finally {
			if (!lock.delete())
				throw new IOException(
						"Could not delete lock file. Should not happen");
			if (tmpIndex.exists() && !tmpIndex.delete())
				throw new IOException(
						"Could not delete temporary index file. Should not happen");
		}
	}

	private void checkWriteOk() throws IOException {
		for (Iterator i = entries.values().iterator(); i.hasNext();) {
			Entry e = (Entry) i.next();
			if (e.getStage() != 0) {
				throw new NotSupportedException("Cannot work with other stages than zero right now. Won't write corrupt index.");
			}
		}
	}

	static boolean File_canExecute( File f){
		return FS.INSTANCE.canExecute(f);
	}

	static boolean File_setExecute(File f, boolean value) {
		return FS.INSTANCE.setExecute(f, value);
	}

	static boolean File_hasExecute() {
		return FS.INSTANCE.supportsExecute();
	}

	static byte[] makeKey(File wd, File f) {
		if (!f.getPath().startsWith(wd.getPath()))
			throw new Error("Path is not in working dir");
		String relName = Repository.stripWorkDir(wd, f);
		return relName.getBytes();
	}

	Boolean filemode;
	private boolean config_filemode() {
		// temporary til we can actually set parameters. We need to be able
		// to change this for testing.
		if (filemode != null)
			return filemode.booleanValue();
		RepositoryConfig config = db.getConfig();
		return config.getBoolean("core", null, "filemode", true);
	}

	/** An index entry */
	public class Entry {
		private long ctime;

		private long mtime;

		private int dev;

		private int ino;

		private int mode;

		private int uid;

		private int gid;

		private int size;

		private ObjectId sha1;

		private short flags;

		private byte[] name;

		Entry(byte[] key, File f, int stage)
				throws IOException {
			ctime = f.lastModified() * 1000000L;
			mtime = ctime; // we use same here
			dev = -1;
			ino = -1;
			if (config_filemode() && File_canExecute(f))
				mode = FileMode.EXECUTABLE_FILE.getBits();
			else
				mode = FileMode.REGULAR_FILE.getBits();
			uid = -1;
			gid = -1;
			size = (int) f.length();
			ObjectWriter writer = new ObjectWriter(db);
			sha1 = writer.writeBlob(f);
			name = key;
			flags = (short) ((stage << 12) | name.length); // TODO: fix flags
		}

		Entry(TreeEntry f, int stage)
				throws UnsupportedEncodingException {
			ctime = -1; // hmm
			mtime = -1;
			dev = -1;
			ino = -1;
			mode = f.getMode().getBits();
			uid = -1;
			gid = -1;
			try {
				size = (int) db.openBlob(f.getId()).getSize();
			} catch (IOException e) {
				e.printStackTrace();
				size = -1;
			}
			sha1 = f.getId();
			name = f.getFullName().getBytes("UTF-8");
			flags = (short) ((stage << 12) | name.length); // TODO: fix flags
		}

		Entry(ByteBuffer b) {
			int startposition = b.position();
			ctime = b.getInt() * 1000000000L + (b.getInt() % 1000000000L);
			mtime = b.getInt() * 1000000000L + (b.getInt() % 1000000000L);
			dev = b.getInt();
			ino = b.getInt();
			mode = b.getInt();
			uid = b.getInt();
			gid = b.getInt();
			size = b.getInt();
			byte[] sha1bytes = new byte[Constants.OBJECT_ID_LENGTH];
			b.get(sha1bytes);
			sha1 = ObjectId.fromRaw(sha1bytes);
			flags = b.getShort();
			name = new byte[flags & 0xFFF];
			b.get(name);
			b
					.position(startposition
							+ ((8 + 8 + 4 + 4 + 4 + 4 + 4 + 4 + 20 + 2
									+ name.length + 8) & ~7));
		}

		/**
		 * Update this index entry with stat and SHA-1 information if it looks
		 * like the file has been modified in the workdir.
		 *
		 * @param f
		 *            file in work dir
		 * @return true if a change occurred
		 * @throws IOException
		 */
		public boolean update(File f) throws IOException {
			boolean modified = false;
			long lm = f.lastModified() * 1000000L;
			if (mtime != lm)
				modified = true;
			mtime = f.lastModified() * 1000000L;
			if (size != f.length())
				modified = true;
			if (config_filemode()) {
				if (File_canExecute(f) != FileMode.EXECUTABLE_FILE.equals(mode)) {
					mode = FileMode.EXECUTABLE_FILE.getBits();
					modified = true;
				}
			}
			if (modified) {
				size = (int) f.length();
				ObjectWriter writer = new ObjectWriter(db);
				ObjectId newsha1 = sha1 = writer.writeBlob(f);
				if (!newsha1.equals(sha1))
					modified = true;
				sha1 = newsha1;
			}
			return modified;
		}

		void write(ByteBuffer buf) {
			int startposition = buf.position();
			buf.putInt((int) (ctime / 1000000000L));
			buf.putInt((int) (ctime % 1000000000L));
			buf.putInt((int) (mtime / 1000000000L));
			buf.putInt((int) (mtime % 1000000000L));
			buf.putInt(dev);
			buf.putInt(ino);
			buf.putInt(mode);
			buf.putInt(uid);
			buf.putInt(gid);
			buf.putInt(size);
			sha1.copyRawTo(buf);
			buf.putShort(flags);
			buf.put(name);
			int end = startposition
					+ ((8 + 8 + 4 + 4 + 4 + 4 + 4 + 4 + 20 + 2 + name.length + 8) & ~7);
			int remain = end - buf.position();
			while (remain-- > 0)
				buf.put((byte) 0);
		}

		/**
		 * Check if an entry's content is different from the cache, 
		 * 
		 * File status information is used and status is same we
		 * consider the file identical to the state in the working
		 * directory. Native git uses more stat fields than we
		 * have accessible in Java.
		 * 
		 * @param wd working directory to compare content with
		 * @return true if content is most likely different.
		 */
		public boolean isModified(File wd) {
			return isModified(wd, false);
		}

		/**
		 * Check if an entry's content is different from the cache, 
		 * 
		 * File status information is used and status is same we
		 * consider the file identical to the state in the working
		 * directory. Native git uses more stat fields than we
		 * have accessible in Java.
		 * 
		 * @param wd working directory to compare content with
		 * @param forceContentCheck True if the actual file content
		 * should be checked if modification time differs.
		 * 
		 * @return true if content is most likely different.
		 */
		public boolean isModified(File wd, boolean forceContentCheck) {

			if (isAssumedValid())
				return false;

			if (isUpdateNeeded())
				return true;

			File file = getFile(wd);
			if (!file.exists())
				return true;

			// JDK1.6 has file.canExecute
			// if (file.canExecute() != FileMode.EXECUTABLE_FILE.equals(mode))
			// return true;
			final int exebits = FileMode.EXECUTABLE_FILE.getBits()
					^ FileMode.REGULAR_FILE.getBits();

			if (config_filemode() && FileMode.EXECUTABLE_FILE.equals(mode)) {
				if (!File_canExecute(file)&& File_hasExecute())
					return true;
			} else {
				if (FileMode.REGULAR_FILE.equals(mode&~exebits)) {
					if (!file.isFile())
						return true;
					if (config_filemode() && File_canExecute(file) && File_hasExecute())
						return true;
				} else {
					if (FileMode.SYMLINK.equals(mode)) {
						return true;
					} else {
						if (FileMode.TREE.equals(mode)) {
							if (!file.isDirectory())
								return true;
						} else {
							System.out.println("Does not handle mode "+mode+" ("+file+")");
							return true;
						}
					}
				}
			}

			if (file.length() != size)
				return true;

			// Git under windows only stores seconds so we round the timestmap
			// Java gives us if it looks like the timestamp in index is seconds
			// only. Otherwise we compare the timestamp at millisecond prevision.
			long javamtime = mtime / 1000000L;
			long lastm = file.lastModified();
			if (javamtime % 1000 == 0)
				lastm = lastm - lastm % 1000;
			if (lastm != javamtime) {
				if (!forceContentCheck)
					return true;

				try {
					InputStream is = new FileInputStream(file);
					ObjectWriter objectWriter = new ObjectWriter(db);
					try {
						ObjectId newId = objectWriter.computeBlobSha1(file
								.length(), is);
						boolean ret = !newId.equals(sha1);
						return ret;
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						try {
							is.close();
						} catch (IOException e) {
							// can't happen, but if it does we ignore it
							e.printStackTrace();
						}
					}
				} catch (FileNotFoundException e) {
					// should not happen because we already checked this
					e.printStackTrace();
					throw new Error(e);
				}
			}
			return false;
		}

		// for testing
		void forceRecheck() {
			mtime = -1;
		}

		private File getFile(File wd) {
			return new File(wd, getName());
		}

		public String toString() {
			return new String(name) + "/SHA-1(" + sha1 + ")/M:"
					+ new Date(ctime / 1000000L) + "/C:"
					+ new Date(mtime / 1000000L) + "/d" + dev + "/i" + ino
					+ "/m" + Integer.toString(mode, 8) + "/u" + uid + "/g"
					+ gid + "/s" + size + "/f" + flags + "/@" + getStage();
		}

		/**
		 * @return path name for this entry
		 */
		public String getName() {
			return new String(name);
		}

		/**
		 * @return path name for this entry as byte array, hopefully UTF-8 encoded
		 */
		public byte[] getNameUTF8() {
			return name;
		}

		/**
		 * @return SHA-1 of the entry managed by this index
		 */
		public ObjectId getObjectId() {
			return sha1;
		}

		/**
		 * @return the stage this entry is in
		 */
		public int getStage() {
			return (flags & 0x3000) >> 12;
		}

		/**
		 * @return size of disk object
		 */
		public int getSize() {
			return size;
		}

		/**
		 * @return true if this entry shall be assumed valid
		 */
		public boolean isAssumedValid() {
			return (flags & 0x8000) != 0;
		}

		/**
		 * @return true if this entry should be checked for changes
		 */
		public boolean isUpdateNeeded() {
			return (flags & 0x4000) != 0;
		}

		/**
		 * Set whether to always assume this entry valid
		 *
		 * @param assumeValid true to ignore changes
		 */
		public void setAssumeValid(boolean assumeValid) {
			if (assumeValid)
				flags |= 0x8000;
			else
				flags &= ~0x8000;
		}

		/**
		 * Set whether this entry must be checked
		 *
		 * @param updateNeeded
		 */
		public void setUpdateNeeded(boolean updateNeeded) {
			if (updateNeeded)
				flags |= 0x4000;
			else
				flags &= ~0x4000;
		}

		/**
		 * Return raw file mode bits. See {@link FileMode}
		 * @return file mode bits
		 */
		public int getModeBits() {
			return mode;
		}
	}

	static class Header {
		private int signature;

		private int version;

		int entries;

		Header(ByteBuffer map) throws CorruptObjectException {
			read(map);
		}

		private void read(ByteBuffer buf) throws CorruptObjectException {
			signature = buf.getInt();
			version = buf.getInt();
			entries = buf.getInt();
			if (signature != 0x44495243)
				throw new CorruptObjectException("Index signature is invalid: "
						+ signature);
			if (version != 2)
				throw new CorruptObjectException(
						"Unknow index version (or corrupt index):" + version);
		}

		void write(ByteBuffer buf) {
			buf.order(ByteOrder.BIG_ENDIAN);
			buf.putInt(signature);
			buf.putInt(version);
			buf.putInt(entries);
		}

		Header(Map entryset) {
			signature = 0x44495243;
			version = 2;
			entries = entryset.size();
		}
	}

	void readTree(Tree t) throws IOException {
		readTree("", t);
	}

	void readTree(String prefix, Tree t) throws IOException {
		TreeEntry[] members = t.members();
		for (int i = 0; i < members.length; ++i) {
			TreeEntry te = members[i];
			String name;
			if (prefix.length() > 0)
				name = prefix + "/" + te.getName();
			else
				name = te.getName();
			if (te instanceof Tree) {
				readTree(name, (Tree) te);
			} else {
				Entry e = new Entry(te, 0);
				entries.put(name.getBytes("UTF-8"), e);
			}
		}
	}
	
	/**
	 * Add tree entry to index
	 * @param te tree entry
	 * @return new or modified index entry
	 * @throws IOException
	 */
	public Entry addEntry(TreeEntry te) throws IOException {
		byte[] key = te.getFullName().getBytes("UTF-8");
		Entry e = new Entry(te, 0);
		entries.put(key, e);
		return e;
	}

	/**
	 * Check out content of the content represented by the index
	 *
	 * @param wd
	 *            workdir
	 * @throws IOException
	 */
	public void checkout(File wd) throws IOException {
		for (Iterator i = entries.values().iterator(); i.hasNext();) {
			Entry e = (Entry) i.next();
			if (e.getStage() != 0)
				continue;
			checkoutEntry(wd, e);
		}
	}
	
	/**
	 * Check out content of the specified index entry
	 *
	 * @param wd workdir
	 * @param e index entry
	 * @throws IOException
	 */
	public void checkoutEntry(File wd, Entry e) throws IOException {
		ObjectLoader ol = db.openBlob(e.sha1);
		byte[] bytes = ol.getBytes();
		File file = new File(wd, e.getName());
		file.delete();
		file.getParentFile().mkdirs();
		FileChannel channel = new FileOutputStream(file).getChannel();
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		int j = channel.write(buffer);
		if (j != bytes.length)
			throw new IOException("Could not write file " + file);
		channel.close();
		if (config_filemode() && File_hasExecute()) {
			if (FileMode.EXECUTABLE_FILE.equals(e.mode)) {
				if (!File_canExecute(file))
					File_setExecute(file, true);
			} else {
				if (File_canExecute(file))
					File_setExecute(file, false);
			}
		}
		e.mtime = file.lastModified() * 1000000L;
		e.ctime = e.mtime;
	}

	/**
	 * Construct and write tree out of index.
	 *
	 * @return SHA-1 of the constructed tree
	 *
	 * @throws IOException
	 */
	public ObjectId writeTree() throws IOException {
		checkWriteOk();
		ObjectWriter writer = new ObjectWriter(db);
		Tree current = new Tree(db);
		Stack trees = new Stack();
		trees.push(current);
		String[] prevName = new String[0];
		for (Iterator i = entries.values().iterator(); i.hasNext();) {
			Entry e = (Entry) i.next();
			if (e.getStage() != 0)
				continue;
			String[] newName = splitDirPath(e.getName());
			int c = longestCommonPath(prevName, newName);
			while (c < trees.size() - 1) {
				current.setId(writer.writeTree(current));
				trees.pop();
				current = trees.isEmpty() ? null : (Tree) trees.peek();
			}
			while (trees.size() < newName.length) {
				if (!current.existsTree(newName[trees.size() - 1])) {
					current = new Tree(current, newName[trees.size() - 1]
							.getBytes());
					current.getParent().addEntry(current);
					trees.push(current);
				} else {
					current = (Tree) current.findTreeMember(newName[trees
							.size() - 1]);
					trees.push(current);
				}
			}
			FileTreeEntry ne = new FileTreeEntry(current, e.sha1,
					newName[newName.length - 1].getBytes(),
					(e.mode & FileMode.EXECUTABLE_FILE.getBits()) == FileMode.EXECUTABLE_FILE.getBits());
			current.addEntry(ne);
		}
		while (!trees.isEmpty()) {
			current.setId(writer.writeTree(current));
			trees.pop();
			if (!trees.isEmpty())
				current = (Tree) trees.peek();
		}
		return current.getTreeId();
	}

	String[] splitDirPath(String name) {
		String[] tmp = new String[name.length() / 2 + 1];
		int p0 = -1;
		int p1;
		int c = 0;
		while ((p1 = name.indexOf('/', p0 + 1)) != -1) {
			tmp[c++] = name.substring(p0 + 1, p1);
			p0 = p1;
		}
		tmp[c++] = name.substring(p0 + 1);
		String[] ret = new String[c];
		for (int i = 0; i < c; ++i) {
			ret[i] = tmp[i];
		}
		return ret;
	}

	int longestCommonPath(String[] a, String[] b) {
		int i;
		for (i = 0; i < a.length && i < b.length; ++i)
			if (!a[i].equals(b[i]))
				return i;
		return i;
	}

	/**
	 * Return the members of the index sorted by the unsigned byte
	 * values of the path names.
	 *
	 * Small beware: Unaccounted for are unmerged entries. You may want
	 * to abort if members with stage != 0 are found if you are doing
	 * any updating operations. All stages will be found after one another
	 * here later. Currently only one stage per name is returned.
	 *
	 * @return The index entries sorted
	 */
	public Entry[] getMembers() {
		return (Entry[]) entries.values().toArray(new Entry[entries.size()]);
	}

	/**
	 * Look up an entry with the specified path.
	 *
	 * @param path
	 * @return index entry for the path or null if not in index.
	 * @throws UnsupportedEncodingException
	 */
	public Entry getEntry(String path) throws UnsupportedEncodingException {
		return (Entry) entries.get(Repository.gitInternalSlash(path.getBytes("ISO-8859-1")));
	}

	/**
	 * @return The repository holding this index.
	 */
	public Repository getRepository() {
		return db;
	}
}
