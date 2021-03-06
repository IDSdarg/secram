/**
 * Copyright © 2013-2016 Swiss Federal Institute of Technology EPFL and Sophia Genetics SA
 * 
 * All rights reserved
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted 
 * provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of 
 * conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of 
 * conditions and the following disclaimer in the documentation and/or other materials provided 
 * with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used 
 * to endorse or promote products derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS 
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY 
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR 
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, 
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER 
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT 
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * PATENTS NOTICE: Sophia Genetics SA holds worldwide pending patent applications in relation with this 
 * software functionality. For more information and licensing conditions, you should contact Sophia Genetics SA 
 * at info@sophiagenetics.com. 
 */
package com.sg.secram.impl;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.seekablestream.SeekableFileStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import java.io.File;
import java.io.IOException;
import com.sg.secram.structure.SecramHeader;
import com.sg.secram.structure.SecramIO;
import com.sg.secram.util.ReferenceUtils;
import com.sg.secram.util.SECRAMUtils;
import com.sg.secram.util.Timings;

/**
 * Reader of a SECRAM file. It supports sequential access and random access.
 * @author zhihuang
 *
 */
public class SECRAMFileReader {
	private SeekableStream inputStream;
	private File secramFile;
	private SecramHeader secramHeader;
	private ReferenceSequenceFile mRsf;
	private SecramIndex secramIndex;
	private SECRAMSecurityFilter filter;

	/**
	 * Construct the reader by specifying the SECRAM file name, the reference file name, and the decryption key.
	 * @param input SECRAM file name.
	 * @param referenceInput Reference file name.
	 * @param key Decryption key.
	 * @throws IOException
	 */
	public SECRAMFileReader(String input, String referenceInput, byte[] key)
			throws IOException {
		secramFile = new File(input);
		inputStream = new SeekableFileStream(secramFile);
		mRsf = ReferenceUtils.findReferenceFile(referenceInput);
		secramIndex = new SecramIndex(new File(secramFile.getAbsolutePath()
				+ ".secrai"));
		filter = new SECRAMSecurityFilter(key);

		readHeader();
	}

	private void readHeader() throws IOException {
		secramHeader = SecramIO.readSecramHeader(inputStream);
	}

	public SecramHeader getSecramHeader() {
		return secramHeader;
	}

	public SAMFileHeader getSAMFileHeader() {
		return secramHeader.getSamFileHeader();
	}

	public SECRAMIterator getCompleteIterator() {
		filter.initPositionEM(secramHeader.getOpeSalt());
		SECRAMIterator secramIterator = new SECRAMIterator(secramHeader,
				inputStream, mRsf, filter);
		return secramIterator;
	}

	/**
	 * Query for a range of positions on the reference.
	 * <p>
	 * NOTE: This method is only used for non-encrypted secram file
	 * @param ref Reference name.
	 * @param start Starting position of the query on the reference.
	 * @param end Ending position of the query on the reference (inclusive).
	 */
	public SECRAMIterator query(String ref, int start, int end)
			throws IOException {
		int refID = secramHeader.getSamFileHeader().getSequenceIndex(ref);
		long absoluteStart = SECRAMUtils.getAbsolutePosition(start, refID), absoluteEnd = SECRAMUtils
				.getAbsolutePosition(end, refID);
		return query(absoluteStart, absoluteEnd);
	}

	/**
	 * Query for a range of positions on the reference.
	 * @param start
	 *            The OPE-encrypted absolute start position
	 * @param end
	 *            The OPE-encrypted absolute end position (inclusive)
	 * @return An iterator over the positions in [start, end]
	 * @throws IOException
	 */
	public SECRAMIterator query(long start, long end) throws IOException {
		long nanoStart = System.nanoTime();
		long offset = secramIndex.getContainerOffset(start);
		if (offset < 0)
			return null;
		inputStream.seek(offset);
		Timings.locateQueryPosition += System.nanoTime() - nanoStart;
		filter.initPositionEM(secramHeader.getOpeSalt());
		filter.setBounds(start, end);
		SECRAMIterator secramIterator = new SECRAMIterator(secramHeader,
				inputStream, mRsf, filter);
		return secramIterator;
	}
}
