package org.broadinstitute.sting.utils.sam;

import org.broadinstitute.sting.gatk.iterators.PeekingStingIterator;
import org.broadinstitute.sting.gatk.Reads;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMFileHeader;

import java.util.Iterator;


/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

/** this fake iterator allows us to look at how specific piles of reads are handled */
public class ArtificialSAMIterator implements PeekingStingIterator {


    protected int currentChromo = 0;
    protected int currentRead = 0;
    protected int totalReadCount = 0;
    protected boolean done = false;
    // the next record
    protected SAMRecord next = null;
    protected SAMFileHeader header = null;

    // the passed in parameters
    protected final int sChr;
    protected final int eChromosomeCount;
    protected final int rCount;
    protected int uMappedReadCount;

    // let us know to make a read, we need this to help out the fake sam query iterator
    private boolean initialized = false;

    /**
     * create the fake iterator, given the mapping of chromosomes and read counts
     *
     * @param startingChr the starting chromosome
     * @param endingChr   the ending chromosome
     * @param readCount   the number of reads in each chromosome
     * @param header      the associated header
     */
    ArtificialSAMIterator( int startingChr, int endingChr, int readCount, SAMFileHeader header ) {
        sChr = startingChr;
        eChromosomeCount = (endingChr - startingChr) + 1;
        rCount = readCount;
        this.header = header;
        this.currentChromo = 0;
        uMappedReadCount = 0;
    }

    /**
     * create the fake iterator, given the mapping of chromosomes and read counts
     *
     * @param startingChr the starting chromosome
     * @param endingChr   the ending chromosome
     * @param readCount   the number of reads in each chromosome
     * @param header      the associated header
     */
    ArtificialSAMIterator( int startingChr, int endingChr, int readCount, int unmappedReadCount, SAMFileHeader header ) {
        sChr = startingChr;
        eChromosomeCount = (endingChr - startingChr) + 1;
        rCount = readCount;
        this.header = header;
        this.currentChromo = 0;
        this.uMappedReadCount = unmappedReadCount;
    }


    public Reads getSourceInfo() {
        throw new UnsupportedOperationException("We don't support this");
    }

    public void close() {
        // done
        currentChromo = Integer.MAX_VALUE;
    }

    public boolean hasNext() {
        if (!initialized){
            initialized = true;
            createNextRead();
        }
        if (this.next != null) {
            return true;
        }
        return false;
    }

    private boolean createNextRead() {
        if (currentRead >= rCount) {
            currentChromo++;
            currentRead = 0;
        }
        // check for end condition, have we finished the chromosome listing, and have no unmapped reads
        if (currentChromo >= eChromosomeCount) {
            if (uMappedReadCount < 1) {
                this.next = null;
                return false;
            } else {
                ++totalReadCount;
                this.next = ArtificialSamUtils.createArtificialRead(this.header, String.valueOf(totalReadCount), -1, -1, 50);
                --uMappedReadCount;
                return true;
            }
        }
        ++totalReadCount;
        this.next = ArtificialSamUtils.createArtificialRead(this.header, String.valueOf(totalReadCount), currentChromo, currentRead, 50);
        ++currentRead;
        return true;
    }


    public SAMRecord next() {
        SAMRecord ret = next;
        createNextRead();
        return ret;
    }

    public void remove() {
        throw new UnsupportedOperationException("You've tried to remove on a StingSAMIterator (unsupported), not to mention that this is a fake iterator.");
    }

    public Iterator<SAMRecord> iterator() {
        return this;
    }

    /**
     * some instrumentation methods
     */
    public int readsTaken() {
        return totalReadCount;
    }

    /**
     * peek at the next sam record
     *
     * @return
     */
    public SAMRecord peek() {
        return this.next;
    }
}
