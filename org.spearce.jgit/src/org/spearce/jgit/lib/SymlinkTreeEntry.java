/*
 *  Copyright (C) 2006  Shawn Pearce <spearce@spearce.org>
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public
 *  License, version 2, as published by the Free Software Foundation.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 */
package org.spearce.jgit.lib;

import java.io.IOException;

/**
 * A tree entry representing a symbolic link.
 *
 * Note. Java cannot really handle these as file system objects.
 */
public class SymlinkTreeEntry extends TreeEntry {
	private static final long serialVersionUID = 1L;

	/**
	 * Construct a {@link SymlinkTreeEntry} with the specified name and SHA-1 in
	 * the specified parent
	 *
	 * @param parent
	 * @param id
	 * @param nameUTF8
	 */
	public SymlinkTreeEntry(final Tree parent, final ObjectId id,
			final byte[] nameUTF8) {
		super(parent, id, nameUTF8);
	}

	public FileMode getMode() {
		return FileMode.SYMLINK;
	}

	public void accept(final TreeVisitor tv, final int flags)
			throws IOException {
		if ((MODIFIED_ONLY & flags) == MODIFIED_ONLY && !isModified()) {
			return;
		}

		tv.visitSymlink(this);
	}

	public String toString() {
		final StringBuffer r = new StringBuffer();
		r.append(ObjectId.toString(getId()));
		r.append(" S ");
		r.append(getFullName());
		return r.toString();
	}
}
