package com.sg.secram.impl.converters;

import htsjdk.samtools.BAMRecord;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.reference.ReferenceSequence;
import htsjdk.samtools.reference.ReferenceSequenceFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sg.secram.SECRAMEncryptionFilter;
import com.sg.secram.compression.SecramRecordCodec;
import com.sg.secram.impl.SECRAMFileWriter;
import com.sg.secram.impl.SECRAMSecurityFilter;
import com.sg.secram.impl.records.PosCigarFeature;
import com.sg.secram.impl.records.PosCigarFeatureCode;
import com.sg.secram.impl.records.SecramRecord;
import com.sg.secram.impl.records.SecramRecordOld;
import com.sg.secram.util.ReferenceUtils;
import com.sg.secram.util.SECRAMUtils;

/**
 * 
 * @author zhicong
 *
 */
public class Bam2Secram {

	private SECRAMEncryptionFilter mEncryptionFilter;
	
	//TODO handle unaligned reads
	//private int mNbUnalignedRead = 0;
	
	private ReferenceSequenceFile mRsf;
	
	private byte[] cachedRefSequence = null;
	private int cachedRefID = -1;
	
	private SAMFileHeader mSAMFileHeader;

	//For my local test invocation
	public Bam2Secram(SAMFileHeader samFileHeader) throws IOException{
		this(samFileHeader, "./data/hs37d5.fa", null);
	}
	
	public Bam2Secram(SAMFileHeader samFileHeader, String referenceInput, SECRAMEncryptionFilter filter) throws IOException {
		this(samFileHeader, ReferenceUtils.findReferenceFile(referenceInput), filter);
	}
	
	private Bam2Secram(SAMFileHeader samFileHeader, ReferenceSequenceFile rsf, SECRAMEncryptionFilter filter) {
		mSAMFileHeader = samFileHeader;
		mRsf = rsf;
		mEncryptionFilter = filter;
	}
	
	public ReferenceSequenceFile getReferenceSequenceFile() {
		return mRsf;
	}

	public SECRAMEncryptionFilter getEncryptionFilter(){
		return mEncryptionFilter;
	}
	
	/**
	 * Returns the {@link SecramRecordOld} instance corresponding to this position. A new instance is created if the position
	 * is accessed for the first time.
	 * @param position The position we want to access
	 * @param secramRecords The map to search for the position
	 * @return the instance of {@link SecramRecordOld} corresponding to this position
	 * @throws IOException
	 */
	private SecramRecordBuilder getBuilder(long position, Map<Long, SecramRecordBuilder> pos2Builder) throws IOException{
		SecramRecordBuilder result;
		if (!pos2Builder.containsKey(position)) {
			
			result = new SecramRecordBuilder((int)(position>>32), (int)position, getReferenceBase(position));
			pos2Builder.put(position,result);
		}
		else {
			result = pos2Builder.get(position);
		}
		return result;
	}
	
	public char getReferenceBase(long pos) throws ArrayIndexOutOfBoundsException,IOException {
		int refID = (int)(pos>>32);
		if (refID == cachedRefID){
			return (char)cachedRefSequence[(int)pos];
		}
		SAMSequenceRecord seq = mSAMFileHeader.getSequence(refID);
		ReferenceSequence rs = mRsf.getSequence(seq.getSequenceName());

		if (rs == null || rs.length() != seq.getSequenceLength()) {
			System.err.println("Could not find the reference sequence " + seq.getSequenceName() + " in the file");
			throw new IOException("No such sequence in file");
		}
		
		cachedRefID = refID;
		cachedRefSequence = rs.getBases();
		
		return (char)cachedRefSequence[(int)pos];
	}
	
	
	public static void main(String[] args) throws Exception {
		File input = new File("./data/HG00115.chrom11.ILLUMINA.bwa.GBR.exome.20130415.bam");
		File output = new File("./data/HG00115.chrom11.ILLUMINA.bwa.GBR.exome.20130415.debugencsecram");
		System.out.println("Start processsing file  \""+ input +"\"");
		long startTime = System.currentTimeMillis();
		
		convertFile(input, output, "SECRET_1SECRET_2SECRET_3".getBytes());
		
		long totalTime = System.currentTimeMillis()-startTime;
		
		long inputLen = input.length();
		long outputLen = output.length();
		double incr = 100*((((double)outputLen)/((double)inputLen))-1);
		
		System.out.println("Processing of file \"" + input + "\" complete.");
		System.out.println("Total time elapsed: "+SECRAMUtils.timeString(totalTime));
		System.out.println("Input size: "+inputLen);
		System.out.println("Output size: "+outputLen);
		System.out.println("Storage increase: "+incr+"%\n");
	}
	
	
	/**
	 * Reads the input file in the BAM format and saves it to the output file in the SECRAM format.
	 * @param input The BAM file to read from. The BAM records *SHOULD* be ordered by their starting positions in the file.
	 * @param output The new SECRAM file to create
	 * @param compression The level of compression applied to the file, should be a value bewteen 0 and 9 (0=no compression, 9=maximum compression)
	 * @param positionsPerBlock how many position are stored in each block
	 * @throws Exception 
	 * @throws {@link IOException} If an {@link IOException} occurs during the operation
	 */
	public static void convertFile(File input, File output, byte[] masterKey) throws Exception {
		SamReader reader = SamReaderFactory.makeDefault().validationStringency(ValidationStringency.SILENT).open(input);
		
		long startTime = System.currentTimeMillis();
		
		SAMFileHeader samFileHeader = reader.getFileHeader();
		
		SECRAMFileWriter secramFileWriter = new SECRAMFileWriter(new BufferedOutputStream(new FileOutputStream(output)), 
				samFileHeader, output.getName(), masterKey);
		
		Bam2Secram converter = new Bam2Secram(samFileHeader);
		
		//a map that returns the record corresponding to this position
		TreeMap<Long, SecramRecordBuilder> pos2Builder = new TreeMap<Long, SecramRecordBuilder>();
		try {
			for (final SAMRecord samRecord : reader) {
				if(samRecord.getReadUnmappedFlag())
					continue;
				BAMRecord bamRecord = (BAMRecord)samRecord;
				converter.addBamRecordToSecramRecords(bamRecord, pos2Builder);
				long startPosition = SECRAMUtils.getAbsolutePosition(bamRecord.getAlignmentStart()-1, bamRecord.getReferenceIndex());
				long pos;
				//Check for any position smaller than the start of this read. Having this loop is to make sure
				//we will write complete secram records out to disk, and thus will not run out of memory.
				while (!pos2Builder.isEmpty() && (pos = pos2Builder.firstKey()) < startPosition) {
					SecramRecordBuilder builder = pos2Builder.remove(pos);
					SecramRecord completedRecord = builder.close(); //removes the position from the list, and saves it
					secramFileWriter.appendRecord(completedRecord);
				}
			}
		}catch(Exception e){
			e.printStackTrace();
		}
		finally {
			//Save the remaining SECRAM records
			while (!pos2Builder.isEmpty()) {
				SecramRecordBuilder remainingBuilder = pos2Builder.remove(pos2Builder.firstKey());
				SecramRecord remainingRecord = remainingBuilder.close();
				secramFileWriter.appendRecord(remainingRecord);
			}
			//Close the writer
			secramFileWriter.close();
			System.out.println("Total number of records written out: " + secramFileWriter.getNumberOfWrittenRecords());
			long totalTime = System.currentTimeMillis()-startTime;
			System.out.println("Total time elapsed: "+SECRAMUtils.timeString(totalTime));
		}
		
	}
	
	public Map<Long, SecramRecordBuilder> createSECRAMRecords(BAMRecord... records) throws IOException{
		TreeMap<Long, SecramRecordBuilder> pos2Builder = new TreeMap<Long, SecramRecordBuilder>();
		for (int i = 0; i < records.length; i++)
			addBamRecordToSecramRecords(records[i], pos2Builder);
		return pos2Builder;
	}
	
	public void addBamRecordToSecramRecords(BAMRecord bamRecord, Map<Long, SecramRecordBuilder> pos2Builder) throws IOException{
		
		long startPosition = SECRAMUtils.getAbsolutePosition(bamRecord.getAlignmentStart()-1, bamRecord.getReferenceIndex());
		
		long endPosition = startPosition + (bamRecord.getCigar().getReferenceLength() - 1);
		
		long pos = startPosition;
		
		SecramRecordBuilder curBuilder  = getBuilder(pos, pos2Builder);
		
		curBuilder.addReadHeader(bamRecord);
		
		String seq = bamRecord.getReadString();
		
		byte[] qualityScores = bamRecord.getBaseQualities();
		int qualityOffset = 0;
		
		boolean starting = true;
		PosCigarFeatureCode code;
		List<PosCigarFeature> features = new LinkedList<PosCigarFeature>();
		
		Iterator<CigarElement> iter = bamRecord.getCigar().getCigarElements().iterator();
		CigarElement element;
		
		if((int)startPosition == 160133)
			System.out.println("trap");
		while(iter.hasNext()) {
			element = iter.next();
			CigarOperator op = element.getOperator();
			int opLength = element.getLength();

			String subSeq; //sub sequence from the read string
			
			//updates the position in the read sequence according to the operator
			switch (op) {
	            case M:
	            case I:
	            case S:
	            case EQ:
	            case X:
	            	subSeq = seq.substring(0,opLength); //retrieve the sub sequence for this operator
	            	seq = seq.substring(opLength); //updates remaining string
	            	break;
	            default:
	            	subSeq = "";
	        }
			
			switch (op) {
				case S:
				case I:
				case H:
				case P:
					code = PosCigarFeatureCode.getFeatureCode(op, starting, false);
					features.add(new PosCigarFeature(curBuilder.getCoverage(),
							code, opLength, subSeq));
					curBuilder.updateScores(qualityScores, qualityOffset, element.getLength());
					qualityOffset += element.getLength();
					break;
				case D:
				case N:
					//
					if(!starting){
						curBuilder.addFeaturesToNextRead(features);
						curBuilder = getBuilder(++pos, pos2Builder);
						features = new LinkedList<PosCigarFeature>();
					}
					//Process the first delete/skip position
					code = PosCigarFeatureCode.getFeatureCode(op, false, false);
					features.add(new PosCigarFeature(curBuilder.getCoverage(),
							code, 1, ""));
					
					//Process the following delete/skip positions
					for (int i = 1; i < opLength; i++){
						curBuilder.addFeaturesToNextRead(features);
						curBuilder = getBuilder(++pos, pos2Builder);
						features = new LinkedList<PosCigarFeature>();
						features.add(new PosCigarFeature(curBuilder.getCoverage(),
								code, 1, ""));
					}
					starting = false;
					break;
				case M:
				case EQ:
				case X:
					if(!starting){
						curBuilder.addFeaturesToNextRead(features);
						curBuilder = getBuilder(++pos, pos2Builder);
						features = new LinkedList<PosCigarFeature>();
					}
					//Process the first match position
					code = PosCigarFeatureCode.getFeatureCode(op, false, false);
					if(curBuilder.getRefBase() != subSeq.charAt(0)){
						features.add(new PosCigarFeature(curBuilder.getCoverage(),
								code, 1, subSeq.substring(0, 1)));
					}
					curBuilder.updateScores(qualityScores, qualityOffset++, 1);
					
					//Process the following match positions
					for (int i = 1; i < opLength; i++) {
						curBuilder.addFeaturesToNextRead(features);
						curBuilder = getBuilder(++pos, pos2Builder);
						features = new LinkedList<PosCigarFeature>();
						if(curBuilder.getRefBase() != subSeq.charAt(i)){
							features.add(new PosCigarFeature(curBuilder.getCoverage(),
									code, 1, subSeq.substring(i, i+1)));
						}
						curBuilder.updateScores(qualityScores, qualityOffset++, 1);
					}
					starting = false;
					break;
			}
			
		}
		//Add features to the last position
		curBuilder.addFeaturesToNextRead(features);
		assert qualityOffset == qualityScores.length : "Quality scores have not been consumed completely";
	}
}