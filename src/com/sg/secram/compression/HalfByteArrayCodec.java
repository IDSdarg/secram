/**
 * ****************************************************************************
 * Copyright 2013 EMBL-EBI
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ****************************************************************************
 */
package com.sg.secram.compression;

import htsjdk.samtools.cram.encoding.AbstractBitCodec;
import htsjdk.samtools.cram.io.BitInputStream;
import htsjdk.samtools.cram.io.BitOutputStream;

import java.io.IOException;

public class HalfByteArrayCodec extends AbstractBitCodec<byte[]> {
	public HalfByteArrayCodec() {
	}

	@Override
	public byte[] read(final BitInputStream bitInputStream) throws IOException {
		throw new RuntimeException("Cannot read byte array of unknown length.");
	}

	@Override
	public byte[] read(BitInputStream bitInputStream, int valueLen)
			throws IOException {
		byte[] array = new byte[valueLen];
		for (int i = 0; i < valueLen; i++)
			array[i] = (byte) bitInputStream.readBits(4);
		return array;
	}

	@Override
	public long write(BitOutputStream bitOutputStream, byte[] object)
			throws IOException {
		for (int i = 0; i < object.length; i++) {
			bitOutputStream.write(object[i], 4);
		}
		return object.length * 4;
	}

	@Override
	public long numberOfBits(byte[] object) {
		// TODO Auto-generated method stub
		return object.length * 4;
	}
}
