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
package org.spearce.jgit.errors;

import java.io.IOException;

import org.spearce.jgit.lib.ObjectId;

/**
 * An inconsistency with respect to handling different object types.
 *
 * This most likely signals a programming error rather than a corrupt
 * object database.
 */
public class IncorrectObjectTypeException extends IOException {
	private static final long serialVersionUID = 1L;

	/**
	 * Construct and IncorrectObjectTypeException for the specified object id.
	 *
	 * Provide the type to make it easier to track down the problem.
	 *
	 * @param id SHA-1
	 * @param type object type
	 */
	public IncorrectObjectTypeException(final ObjectId id, final String type) {
		super("Object " + id + " is not a " + type + ".");
	}
}
