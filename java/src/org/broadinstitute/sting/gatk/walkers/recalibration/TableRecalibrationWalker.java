package org.broadinstitute.sting.gatk.walkers.recalibration;

import net.sf.samtools.*;
import net.sf.samtools.util.SequenceUtil;
import org.broadinstitute.sting.gatk.walkers.ReadWalker;
import org.broadinstitute.sting.gatk.walkers.WalkerName;
import org.broadinstitute.sting.gatk.walkers.Requires;
import org.broadinstitute.sting.gatk.walkers.DataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.io.StingSAMFileWriter;
import org.broadinstitute.sting.utils.cmdLine.*;
import org.broadinstitute.sting.utils.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;

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
 * This walker is designed to work as the second pass in a two-pass processing step, doing a by-read traversal.

 * For each base in each read this walker calculates various user-specified covariates (such as read group, reported quality score, cycle, and dinuc)
 * Using these values as a key in a large hashmap the walker calculates an empirical base quality score and overwrites the quality score currently in the read.
 * This walker then outputs a new bam file with these updated (recalibrated) reads.
 * 
 * Note: This walker expects as input the recalibration table file generated previously by CovariateCounterWalker.
 * Note: This walker is designed to be used in conjunction with CovariateCounterWalker.
 *
 * @author rpoplin
 * @since Nov 3, 2009
 * @help.summary Second pass of the recalibration. Uses the table generated by CountCovariates to update the base quality scores of the input bam file using a sequential table calculation making the base quality scores more accurately reflect the actual quality of the bases as mesaured by reference mismatch rate.
 */

@WalkerName("TableRecalibration")
@Requires({ DataSource.READS, DataSource.REFERENCE, DataSource.REFERENCE_BASES }) // This walker requires -I input.bam, it also requires -R reference.fasta
public class TableRecalibrationWalker extends ReadWalker<SAMRecord, SAMFileWriter> {

    /////////////////////////////
    // Shared Arguments
    /////////////////////////////
    @ArgumentCollection private RecalibrationArgumentCollection RAC = new RecalibrationArgumentCollection();

    /////////////////////////////
    // Command Line Arguments
    /////////////////////////////
    @Argument(fullName="output_bam", shortName="outputBam", doc="The output BAM file", required=true)
    private StingSAMFileWriter OUTPUT_BAM;
    @Argument(fullName="preserve_qscores_less_than", shortName="pQ",
        doc="Bases with quality scores less than this threshold won't be recalibrated, default=5. In general it's unsafe to change qualities scores below < 5, since base callers use these values to indicate random or bad bases", required=false)
    private int PRESERVE_QSCORES_LESS_THAN = 5;
    @Argument(fullName="smoothing", shortName="sm", required = false, doc="Number of imaginary counts to add to each bin in order to smooth out bins with few data points")
    private int SMOOTHING = 1;
    @Argument(fullName="max_quality_score", shortName="maxQ", required = false, doc="The integer value at which to cap the quality scores, default is 40")
    private int MAX_QUALITY_SCORE = 40;
    
    /////////////////////////////
    // Debugging-only Arguments
    /////////////////////////////
    @Argument(fullName="no_pg_tag", shortName="noPG", required=false, doc="Don't output the usual PG tag in the recalibrated bam file header. FOR DEBUGGING PURPOSES ONLY. This option is required in order to pass integration tests.")
    private boolean NO_PG_TAG = false;

    /////////////////////////////
    // Private Member Variables
    /////////////////////////////
    private RecalDataManager dataManager; // Holds the data HashMap, mostly used by TableRecalibrationWalker to create collapsed data hashmaps
    private ArrayList<Covariate> requestedCovariates; // List of covariates to be used in this calculation
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^#.*");
    private static final Pattern OLD_RECALIBRATOR_HEADER = Pattern.compile("^rg,.*");
    private static final Pattern COVARIATE_PATTERN = Pattern.compile("^ReadGroup,QualityScore,.*");
    private static final String versionString = "v2.2.9"; // Major version, minor version, and build number
    private Random coinFlip; // Random number generator is used to remove reference bias in solid bams
    private static final long RANDOM_SEED = 1032861495;

    //---------------------------------------------------------------------------------------------------------------
    //
    // initialize
    //
    //---------------------------------------------------------------------------------------------------------------

    /**
     * Read in the recalibration table input file.
     * Parse the list of covariate classes used during CovariateCounterWalker.
     * Parse the CSV data and populate the hashmap.
     */
    public void initialize() {

        logger.info( "TableRecalibrationWalker version: " + versionString );
        if( RAC.FORCE_READ_GROUP != null ) { RAC.DEFAULT_READ_GROUP = RAC.FORCE_READ_GROUP; }
        if( RAC.FORCE_PLATFORM != null ) { RAC.DEFAULT_PLATFORM = RAC.FORCE_PLATFORM; }
        coinFlip = new Random(RANDOM_SEED);
        if( !RAC.checkSolidRecalMode() ) {
            throw new StingException( "Unrecognized --solid_recal_mode argument. Implemented options: DO_NOTHING, SET_Q_ZERO, SET_Q_ZERO_BASE_N, or REMOVE_REF_BIAS");
        }

        // Get a list of all available covariates
        final List<Class<? extends Covariate>> classes = PackageUtils.getClassesImplementingInterface(Covariate.class);

        int lineNumber = 0;
        boolean foundAllCovariates = false;

        // Warn the user if a dbSNP file was specified since it isn't being used here
        boolean foundDBSNP = false;
        for( ReferenceOrderedDataSource rod : this.getToolkit().getRodDataSources() ) {
            if( rod.getName().equalsIgnoreCase( "dbsnp" ) ) {
                foundDBSNP = true;
            }
        }
        if( foundDBSNP ) {
            Utils.warnUser("A dbSNP rod file was specified but TableRecalibrationWalker doesn't make use of it.");
        }

        // Read in the covariates that were used from the input file
        requestedCovariates = new ArrayList<Covariate>();

        // Read in the data from the csv file and populate the map
        logger.info( "Reading in the data from input csv file..." );

        try {
            for ( String line : new xReadLines(new File( RAC.RECAL_FILE )) ) {
                lineNumber++;
                if( COMMENT_PATTERN.matcher(line).matches() || OLD_RECALIBRATOR_HEADER.matcher(line).matches())  {
                    ; // Skip over the comment lines, (which start with '#')
                }
                else if( COVARIATE_PATTERN.matcher(line).matches() ) { // The line string is either specifying a covariate or is giving csv data
                    if( foundAllCovariates ) {
                        throw new StingException( "Malformed input recalibration file. Found covariate names intermingled with data in file: " + RAC.RECAL_FILE );
                    } else { // Found the covariate list in input file, loop through all of them and instantiate them
                        String[] vals = line.split(",");
                        for( int iii = 0; iii < vals.length - 3; iii++ ) { // There are n-3 covariates. The last three items are nObservations, nMismatch, and Qempirical
                            boolean foundClass = false;
                            for( Class<?> covClass : classes ) {
                                if( (vals[iii] + "Covariate").equalsIgnoreCase( covClass.getSimpleName() ) ) {
                                    foundClass = true;
                                    try {
                                        Covariate covariate = (Covariate)covClass.newInstance();
                                        requestedCovariates.add( covariate );

                                    } catch ( InstantiationException e ) {
                                        throw new StingException( String.format("Can not instantiate covariate class '%s': must be concrete class.", covClass.getSimpleName()) );
                                    } catch ( IllegalAccessException e ) {
                                        throw new StingException( String.format("Can not instantiate covariate class '%s': must have no-arg constructor.", covClass.getSimpleName()) );
                                    }
                                }
                            }

                            if( !foundClass ) {
                                throw new StingException( "Malformed input recalibration file. The requested covariate type (" + (vals[iii] + "Covariate") + ") isn't a valid covariate option." );
                            }
                        }
                    }

                } else { // Found a line of data
                    if( !foundAllCovariates ) {
                        foundAllCovariates = true;

                        // At this point all the covariates should have been found and initialized
                        if( requestedCovariates.size() < 2 ) {
                            throw new StingException( "Malformed input recalibration file. Covariate names can't be found in file: " + RAC.RECAL_FILE );
                        }

                        final boolean createCollapsedTables = true;

                        // Initialize any covariate member variables using the shared argument collection
                        for( Covariate cov : requestedCovariates ) {
                            cov.initialize( RAC );
                        }
                        // Initialize the data hashMaps
                        dataManager = new RecalDataManager( createCollapsedTables, requestedCovariates.size() );

                    }
                    addCSVData(line); // Parse the line and add the data to the HashMap
                }
            }

        } catch ( FileNotFoundException e ) {
            throw new StingException("Can not find input file: " + RAC.RECAL_FILE);
        } catch ( NumberFormatException e ) {
            throw new StingException("Error parsing recalibration data at line " + lineNumber + ". Perhaps your table was generated by an older version of CovariateCounterWalker.");
        }
        logger.info( "...done!" );

        logger.info( "The covariates being used here: " );
        for( Covariate cov : requestedCovariates ) {
            logger.info( "\t" + cov.getClass().getSimpleName() );
        }

        if( dataManager == null ) {
            throw new StingException("Can't initialize the data manager. Perhaps the recal csv file contains no data?");
        }

        // Create the tables of empirical quality scores that will be used in the sequential calculation
        logger.info( "Generating tables of empirical qualities for use in sequential calculation..." );
        dataManager.generateEmpiricalQualities( SMOOTHING, MAX_QUALITY_SCORE );
        logger.info( "...done!" );

        // Take the header of the input SAM file and tweak it by adding in a new programRecord with the version number and list of covariates that were used
        final SAMFileHeader header = getToolkit().getSAMFileHeader().clone();
        if( !NO_PG_TAG ) {
            final SAMProgramRecord programRecord = new SAMProgramRecord( "GATK TableRecalibration" );
            programRecord.setProgramVersion( versionString );
            String commandLineString = "Covariates used = [";
            for( Covariate cov : requestedCovariates ) {
                commandLineString += cov.getClass().getSimpleName() + ", ";
            }
            commandLineString = commandLineString.substring(0, commandLineString.length() - 2); // trim off the trailing comma
            commandLineString += "] ";
            // Add all of the arguments from the recalibraiton argument collection to the command line string
            final Field[] fields = RAC.getClass().getFields();
            for( Field field : fields ) {
                final ArgumentTypeDescriptor atd = ArgumentTypeDescriptor.create(field.getType());
                final List<ArgumentDefinition> adList = atd.createArgumentDefinitions(new ArgumentSource(field.getType(), field));
                for( ArgumentDefinition ad : adList ) {
                    commandLineString += (ad.fullName + "=" + JVMUtils.getFieldValue(field, RAC) + ", ");
                }
            }
            commandLineString += "pQ = " + PRESERVE_QSCORES_LESS_THAN + ", ";
            commandLineString += "smoothing = " + SMOOTHING;
            programRecord.setCommandLine( commandLineString );
            header.addProgramRecord( programRecord );

            // write out the new header
            OUTPUT_BAM.writeHeader( header );
        }
    }

    /**
     * For each covariate read in a value and parse it. Associate those values with the data itself (num observation and num mismatches)
     * @param line A line of CSV data read from the recalibration table data file
     */
    private void addCSVData(final String line) {
        final String[] vals = line.split(",");

        // Check if the data line is malformed, for example if the read group string contains a comma then it won't be parsed correctly
        if( vals.length != requestedCovariates.size() + 3 ) { // +3 because of nObservations, nMismatch, and Qempirical
            throw new StingException("Malformed input recalibration file. Found data line with too many fields: " + line +
                    " --Perhaps the read group string contains a comma and isn't being parsed correctly.");
        }

        final Object[] key = new Object[requestedCovariates.size()];
        Covariate cov;
        int iii;
        for( iii = 0; iii < requestedCovariates.size(); iii++ ) {
            cov = requestedCovariates.get( iii );
            key[iii] = cov.getValue( vals[iii] );
        }

        // Create a new datum using the number of observations, number of mismatches, and reported quality score
        final RecalDatum datum = new RecalDatum( Long.parseLong( vals[iii] ), Long.parseLong( vals[iii + 1] ), Double.parseDouble( vals[1] ), 0.0 );
        // Add that datum to all the collapsed tables which will be used in the sequential calculation
        dataManager.addToAllTables( key, datum, PRESERVE_QSCORES_LESS_THAN );
        
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // map
    //
    //---------------------------------------------------------------------------------------------------------------

    /**
     * For each base in the read calculate a new recalibrated quality score and replace the quality scores in the read
     * @param refBases References bases over the length of the read
     * @param read The read to be recalibrated
     * @return The read with quality scores replaced
     */
    public SAMRecord map( char[] refBases, SAMRecord read ) {

        RecalDataManager.parseSAMRecord( read, RAC );
        
        byte[] originalQuals = read.getBaseQualities();
        final byte[] recalQuals = originalQuals.clone();

        final String platform = read.getReadGroup().getPlatform();
        if( platform.equalsIgnoreCase("SOLID") && !RAC.SOLID_RECAL_MODE.equalsIgnoreCase("DO_NOTHING") ) {
            originalQuals = RecalDataManager.calcColorSpace( read, originalQuals, RAC.SOLID_RECAL_MODE, coinFlip, refBases );
        }

        final Object[] fullCovariateKey = new Object[requestedCovariates.size()];

        // For each base in the read
        final int readLength = read.getReadLength();
        for( int offset = 0; offset < readLength; offset++ ) {

            // Loop through the list of requested covariates and pick out the value from the read and offset
            int iii = 0;
            for( Covariate covariate : requestedCovariates ) {
                fullCovariateKey[iii++] = covariate.getValue( read, offset );
            }

            recalQuals[offset] = performSequentialQualityCalculation( fullCovariateKey );
        }

        preserveQScores( originalQuals, recalQuals ); // Overwrite the work done if original quality score is too low

        read.setBaseQualities( recalQuals ); // Overwrite old qualities with new recalibrated qualities
        if ( read.getAttribute(RecalDataManager.ORIGINAL_QUAL_ATTRIBUTE_TAG) == null ) { // Save the old qualities if the tag isn't already taken in the read
            read.setAttribute(RecalDataManager.ORIGINAL_QUAL_ATTRIBUTE_TAG, QualityUtils.phredToFastq(originalQuals));
        }
        if (read.getAttribute(SAMTag.UQ.name()) != null) {
            // TODO - When refBases is switches to byte[], call the appropriate overload in SAM-JDK, and remove char[] overload from SAM-JDK.
            read.setAttribute(SAMTag.UQ.name(), SequenceUtil.sumQualitiesOfMismatches(read, refBases, read.getAlignmentStart() - 1));
        }

        return read;
    }

    /**
     * Implements a serial recalibration of the reads using the combinational table.
     * First, we perform a positional recalibration, and then a subsequent dinuc correction.
     *
     * Given the full recalibration table, we perform the following preprocessing steps:
     *
     *   - calculate the global quality score shift across all data [DeltaQ]
     *   - calculate for each of cycle and dinuc the shift of the quality scores relative to the global shift
     *      -- i.e., DeltaQ(dinuc) = Sum(pos) Sum(Qual) Qempirical(pos, qual, dinuc) - Qreported(pos, qual, dinuc) / Npos * Nqual
     *   - The final shift equation is:
     *
     *      Qrecal = Qreported + DeltaQ + DeltaQ(pos) + DeltaQ(dinuc) + DeltaQ( ... any other covariate ... ) 
     * @param key The list of Comparables that were calculated from the covariates
     * @return A recalibrated quality score as a byte
     */
    private byte performSequentialQualityCalculation( final Object... key ) {

        final byte qualFromRead = (byte)Integer.parseInt(key[1].toString());
        final Object[] readGroupCollapsedKey = new Object[1];
        final Object[] qualityScoreCollapsedKey = new Object[2];
        final Object[] covariateCollapsedKey = new Object[3];

        // The global quality shift (over the read group only)
        readGroupCollapsedKey[0] = key[0];
        final RecalDatum globalRecalDatum = ((RecalDatum)dataManager.getCollapsedTable(0).get( readGroupCollapsedKey ));
        double globalDeltaQ = 0.0;
        if( globalRecalDatum != null ) {
            final double globalDeltaQEmpirical = globalRecalDatum.getEmpiricalQuality();
            final double aggregrateQReported = globalRecalDatum.getEstimatedQReported();
            globalDeltaQ = globalDeltaQEmpirical - aggregrateQReported;
        }

        // The shift in quality between reported and empirical
        qualityScoreCollapsedKey[0] = key[0];
        qualityScoreCollapsedKey[1] = key[1];
        final RecalDatum qReportedRecalDatum = ((RecalDatum)dataManager.getCollapsedTable(1).get( qualityScoreCollapsedKey ));
        double deltaQReported = 0.0;
        if( qReportedRecalDatum != null ) {
            final double deltaQReportedEmpirical = qReportedRecalDatum.getEmpiricalQuality();
            deltaQReported = deltaQReportedEmpirical - qualFromRead - globalDeltaQ;
        }
        
        // The shift in quality due to each covariate by itself in turn
        double deltaQCovariates = 0.0;
        double deltaQCovariateEmpirical;
        covariateCollapsedKey[0] = key[0];
        covariateCollapsedKey[1] = key[1];
        for( int iii = 2; iii < key.length; iii++ ) {
            covariateCollapsedKey[2] =  key[iii]; // The given covariate
            final RecalDatum covariateRecalDatum = ((RecalDatum)dataManager.getCollapsedTable(iii).get( covariateCollapsedKey ));
            if( covariateRecalDatum != null ) {
                deltaQCovariateEmpirical = covariateRecalDatum.getEmpiricalQuality();
                deltaQCovariates += ( deltaQCovariateEmpirical - qualFromRead - (globalDeltaQ + deltaQReported) );
            }
        }

        final double newQuality = qualFromRead + globalDeltaQ + deltaQReported + deltaQCovariates;
        return QualityUtils.boundQual( (int)Math.round(newQuality), (byte)MAX_QUALITY_SCORE );

        // Verbose printouts used to validate with old recalibrator
        //if(key.contains(null)) {
        //    System.out.println( key  + String.format(" => %d + %.2f + %.2f + %.2f + %.2f = %d",
        //                 qualFromRead, globalDeltaQ, deltaQReported, deltaQPos, deltaQDinuc, newQualityByte));
        //}
        //else {
        //    System.out.println( String.format("%s %s %s %s => %d + %.2f + %.2f + %.2f + %.2f = %d",
        //                 key.get(0).toString(), key.get(3).toString(), key.get(2).toString(), key.get(1).toString(), qualFromRead, globalDeltaQ, deltaQReported, deltaQPos, deltaQDinuc, newQualityByte) );
        //}

        //return newQualityByte;
    }

    /**
     * Loop over the list of qualities and overwrite the newly recalibrated score to be the original score if it was less than some threshold
     * @param originalQuals The list of original base quality scores
     * @param recalQuals A list of the new recalibrated quality scores
     */
    private void preserveQScores( final byte[] originalQuals, final byte[] recalQuals ) {
        for( int iii = 0; iii < recalQuals.length; iii++ ) {
            if( originalQuals[iii] < PRESERVE_QSCORES_LESS_THAN ) {
                recalQuals[iii] = originalQuals[iii];
            }
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // reduce
    //
    //---------------------------------------------------------------------------------------------------------------

    /**
     * Start the reduce with a handle to the output bam file
     * @return A FileWriter pointing to a new bam file
     */
    public SAMFileWriter reduceInit() {
        return OUTPUT_BAM;
    }

    /**
     * Output each read to disk
     * @param read The read to output
     * @param output The FileWriter to write the read to
     * @return The FileWriter
     */
    public SAMFileWriter reduce( SAMRecord read, SAMFileWriter output ) {
        if( output != null ) {
            output.addAlignment(read);
        } else {
            out.println(read.format());
        }

        return output;
    }

    /**
     * Do nothing
     * @param output The SAMFileWriter that outputs the bam file
     */
    public void onTraversalDone(SAMFileWriter output) {
    }
}
