package org.broadinstitute.sting.utils.sam;

import org.broadinstitute.sting.BaseTest;
import org.broadinstitute.sting.gatk.iterators.PeekingStingIterator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import net.sf.samtools.SAMRecord;
import static junit.framework.Assert.assertTrue;


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

/**
 * @author aaron
 *         <p/>
 *         Class ArtificialSAMQueryIteratorTest
 *         <p/>
 *         a test for the ArtificialSAMQueryIterator class.
 */
public class ArtificialSAMQueryIteratorTest extends BaseTest {

    @Test
    public void testWholeChromosomeQuery() {
        ArtificialSAMQueryIterator iter = ArtificialSamUtils.queryReadIterator(1, 2, 100);
        iter.queryContained("chr1", 1, -1);
        int count = 0;
        while (iter.hasNext()) {
            SAMRecord rec = iter.next();
            count++;
        }
        assertEquals(100, count);

    }

    @Test
    public void testContainedQueryStart() {
        ArtificialSAMQueryIterator iter = ArtificialSamUtils.queryReadIterator(1, 2, 100);
        iter.queryContained("chr1", 1, 50);
        int count = 0;
        while (iter.hasNext()) {
            SAMRecord rec = iter.next();
            count++;
        }
        assertEquals(1, count);

    }

    @Test
    public void testOverlappingQueryStart() {
        ArtificialSAMQueryIterator iter = ArtificialSamUtils.queryReadIterator(1, 2, 100);
        iter.queryOverlapping("chr1", 1, 50);
        int count = 0;
        while (iter.hasNext()) {
            SAMRecord rec = iter.next();
            count++;
        }
        assertEquals(50, count);

    }

    @Test
    public void testContainedQueryMiddle() {
        ArtificialSAMQueryIterator iter = ArtificialSamUtils.queryReadIterator(1, 2, 100);
        iter.queryContained("chr1", 25, 74);
        int count = 0;
        while (iter.hasNext()) {
            SAMRecord rec = iter.next();
            count++;
        }
        assertEquals(1, count);

    }

    @Test
    public void testOverlappingQueryMiddle() {
        ArtificialSAMQueryIterator iter = ArtificialSamUtils.queryReadIterator(1, 2, 100);
        iter.queryOverlapping("chr1", 25, 74);
        int count = 0;
        while (iter.hasNext()) {
            SAMRecord rec = iter.next();
            count++;
        }
        assertEquals(50, count);

    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownChromosome() {
        ArtificialSAMQueryIterator iter = ArtificialSamUtils.queryReadIterator(1, 2, 100);
        iter.queryOverlapping("chr621", 25, 74);         
    }
}
