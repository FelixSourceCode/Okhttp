/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package libcore.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.icu.util.TimeZone;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A structure that can find matching time zones.
 */
public class TimeZoneFinder {

    private static final String TZLOOKUP_FILE_NAME = "tzlookup.xml";
    private static final String TIMEZONES_ELEMENT = "timezones";
    private static final String IANA_VERSION_ATTRIBUTE = "ianaversion";
    private static final String COUNTRY_ZONES_ELEMENT = "countryzones";
    private static final String COUNTRY_ELEMENT = "country";
    private static final String COUNTRY_CODE_ATTRIBUTE = "code";
    private static final String DEFAULT_TIME_ZONE_ID_ATTRIBUTE = "default";
    private static final String ID_ELEMENT = "id";

    private static TimeZoneFinder instance;

    private final ReaderSupplier xmlSource;

    // Cached field for the last country looked up.
    private CountryTimeZones lastCountryTimeZones;

    private TimeZoneFinder(ReaderSupplier xmlSource) {
        this.xmlSource = xmlSource;
    }

    /**
     * Obtains an instance for use when resolving time zones. This method handles using the correct
     * file when there are several to choose from. This method never returns {@code null}. No
     * in-depth validation is performed on the file content, see {@link #validate()}.
     */
    public static TimeZoneFinder getInstance() {
        synchronized(TimeZoneFinder.class) {
            if (instance == null) {
                String[] tzLookupFilePaths =
                        TimeZoneDataFiles.getTimeZoneFilePaths(TZLOOKUP_FILE_NAME);
                instance = createInstanceWithFallback(tzLookupFilePaths[0], tzLookupFilePaths[1]);
            }
        }
        return instance;
    }

    // VisibleForTesting
    public static TimeZoneFinder createInstanceWithFallback(String... tzLookupFilePaths) {
        IOException lastException = null;
        for (String tzLookupFilePath : tzLookupFilePaths) {
            try {
                // We assume that any file in /data was validated before install, and the system
                // file was validated before the device shipped. Therefore, we do not pay the
                // validation cost here.
                return createInstance(tzLookupFilePath);
            } catch (IOException e) {
                // There's expected to be two files, and it's normal for the first file not to
                // exist so we don't log, but keep the lastException so we can log it if there
                // are no valid files available.
                if (lastException != null) {
                    e.addSuppressed(lastException);
                }
                lastException = e;
            }
        }

        System.logE("No valid file found in set: " + Arrays.toString(tzLookupFilePaths)
                + " Printing exceptions and falling back to empty map.", lastException);
        return createInstanceForTests("<timezones><countryzones /></timezones>");
    }

    /**
     * Obtains an instance using a specific data file, throwing an IOException if the file does not
     * exist or is not readable. This method never returns {@code null}. No in-depth validation is
     * performed on the file content, see {@link #validate()}.
     */
    public static TimeZoneFinder createInstance(String path) throws IOException {
        ReaderSupplier xmlSupplier = ReaderSupplier.forFile(path, StandardCharsets.UTF_8);
        return new TimeZoneFinder(xmlSupplier);
    }

    /** Used to create an instance using an in-memory XML String instead of a file. */
    // VisibleForTesting
    public static TimeZoneFinder createInstanceForTests(String xml) {
        return new TimeZoneFinder(ReaderSupplier.forString(xml));
    }

    /**
     * Parses the data file, throws an exception if it is invalid or cannot be read.
     */
    public void validate() throws IOException {
        try {
            processXml(new CountryZonesValidator());
        } catch (XmlPullParserException e) {
            throw new IOException("Parsing error", e);
        }
    }

    /**
     * Returns the IANA rules version associated with the data. If there is no version information
     * or there is a problem reading the file then {@code null} is returned.
     */
    public String getIanaVersion() {
        try (Reader reader = xmlSource.get()) {
            XmlPullParserFactory xmlPullParserFactory = XmlPullParserFactory.newInstance();
            xmlPullParserFactory.setNamespaceAware(false);

            XmlPullParser parser = xmlPullParserFactory.newPullParser();
            parser.setInput(reader);

            /*
             * The expected XML structure is:
             * <timezones ianaversion="xxxxx">
             *     ....
             * </timezones>
             */

            findRequiredStartTag(parser, TIMEZONES_ELEMENT);

            return parser.getAttributeValue(null /* namespace */, IANA_VERSION_ATTRIBUTE);
        } catch (XmlPullParserException | IOException e) {
            return null;
        }
    }

    /**
     * Returns a frozen ICU time zone that has / would have had the specified offset and DST value
     * at the specified moment in the specified country.
     *
     * <p>In order to be considered a configured zone must match the supplied offset information.
     *
     * <p>Matches are considered in a well-defined order. If multiple zones match and one of them
     * also matches the (optional) bias parameter then the bias time zone will be returned.
     * Otherwise the first match found is returned.
     */
    public TimeZone lookupTimeZoneByCountryAndOffset(
            String countryIso, int offsetSeconds, boolean isDst, long whenMillis, TimeZone bias) {

        countryIso = normalizeCountryIso(countryIso);
        List<TimeZone> candidates = lookupTimeZonesByCountry(countryIso);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        TimeZone firstMatch = null;
        for (TimeZone match : candidates) {
            if (!offsetMatchesAtTime(match, offsetSeconds, isDst, whenMillis)) {
                continue;
            }

            if (firstMatch == null) {
                if (bias == null) {
                    // No bias, so we can stop at the first match.
                    return match;
                }
                // We have to carry on checking in case the bias matches. We want to return the
                // first if it doesn't, though.
                firstMatch = match;
            }

            // Check if match is also the bias. There must be a bias otherwise we'd have terminated
            // already.
            if (match.getID().equals(bias.getID())) {
                return match;
            }
        }
        // Return firstMatch, which can be null if there was no match.
        return firstMatch;
    }

    /**
     * Returns {@code true} if the specified offset, DST state and time would be valid in the
     * timeZone.
     */
    private static boolean offsetMatchesAtTime(TimeZone timeZone, int offsetMillis, boolean isDst,
            long whenMillis) {
        int[] offsets = new int[2];
        timeZone.getOffset(whenMillis, false /* local */, offsets);

        // offsets[1] == 0 when the zone is not in DST.
        boolean zoneIsDst = offsets[1] != 0;
        if (isDst != zoneIsDst) {
            return false;
        }
        return offsetMillis == (offsets[0] + offsets[1]);
    }

    /**
     * Returns a "default" time zone ID known to be used in the specified country. This is
     * the time zone ID that can be used if only the country code is known and can be presumed to be
     * the "best" choice in the absence of other information. For countries with more than one zone
     * the time zone will not be correct for everybody.
     *
     * <p>If the country code is not recognized or there is an error during lookup this can return
     * null.
     */
    public String lookupDefaultTimeZoneIdByCountry(String countryIso) {
        countryIso = normalizeCountryIso(countryIso);
        CountryTimeZones countryTimeZones = findCountryTimeZones(countryIso);
        return countryTimeZones == null ? null : countryTimeZones.getDefaultTimeZoneId();
    }

    /**
     * Returns an immutable list of frozen ICU time zones known to be used in the specified country.
     * If the country code is not recognized or there is an error during lookup this can return
     * null. The TimeZones returned will never contain {@link TimeZone#UNKNOWN_ZONE}. This method
     * can return an empty list in a case when the underlying data files reference only unknown
     * zone IDs.
     */
    public List<TimeZone> lookupTimeZonesByCountry(String countryIso) {
        countryIso = normalizeCountryIso(countryIso);
        CountryTimeZones countryTimeZones = findCountryTimeZones(countryIso);
        return countryTimeZones == null ? null : countryTimeZones.getTimeZones();
    }

    /**
     * Returns an immutable list of time zone IDs known to be used in the specified country.
     * If the country code is not recognized or there is an error during lookup this can return
     * null. The IDs returned will all be valid for use with
     * {@link java.util.TimeZone#getTimeZone(String)} and
     * {@link TimeZone#getTimeZone(String)}. This method can return an empty list
     * in a case when the underlying data files reference only unknown zone IDs.
     */
    public List<String> lookupTimeZoneIdsByCountry(String countryIso) {
        countryIso = normalizeCountryIso(countryIso);
        CountryTimeZones countryTimeZones = findCountryTimeZones(countryIso);
        return countryTimeZones == null ? null : countryTimeZones.getTimeZoneIds();
    }

    /**
     * Returns a {@link CountryTimeZones} object associated with the specified country code.
     * Caching is handled as needed. If the country code is not recognized or there is an error
     * during lookup this can return null.
     */
    private CountryTimeZones findCountryTimeZones(String countryIso) {
        synchronized (this) {
            if (lastCountryTimeZones != null
                    && lastCountryTimeZones.getCountryIso().equals(countryIso)) {
                return lastCountryTimeZones;
            }
        }

        SelectiveCountryTimeZonesExtractor extractor =
                new SelectiveCountryTimeZonesExtractor(countryIso);
        try {
            processXml(extractor);

            CountryTimeZones countryTimeZones = extractor.getValidatedCountryTimeZones();
            if (countryTimeZones == null) {
                // None matched. Return the null but don't change the cached value.
                return null;
            }

            // Update the cached value.
            synchronized (this) {
                lastCountryTimeZones = countryTimeZones;
            }
            return countryTimeZones;
        } catch (XmlPullParserException | IOException e) {
            System.logW("Error reading country zones ", e);

            // Error - don't change the cached value.
            return null;
        }
    }

    /**
     * Processes the XML, applying the {@link CountryZonesProcessor} to the &lt;countryzones&gt;
     * element. Processing can terminate early if the
     * {@link CountryZonesProcessor#process(String, String, List, String)} returns
     * {@link CountryZonesProcessor#HALT} or it throws an exception.
     */
    private void processXml(CountryZonesProcessor processor)
            throws XmlPullParserException, IOException {
        try (Reader reader = xmlSource.get()) {
            XmlPullParserFactory xmlPullParserFactory = XmlPullParserFactory.newInstance();
            xmlPullParserFactory.setNamespaceAware(false);

            XmlPullParser parser = xmlPullParserFactory.newPullParser();
            parser.setInput(reader);

            /*
             * The expected XML structure is:
             * <timezones ianaversion="2017b">
             *   <countryzones>
             *     <country code="us" default="America/New_York">
             *       <id>America/New_York"</id>
             *       ...
             *       <id>America/Los_Angeles</id>
             *     </country>
             *     <country code="gb" default="Europe/London">
             *       <id>Europe/London</id>
             *     </country>
             *   </countryzones>
             * </timezones>
             */

            findRequiredStartTag(parser, TIMEZONES_ELEMENT);

            // We do not require the ianaversion attribute be present. It is metadata that helps
            // with versioning but is not required.

            // There is only one expected sub-element <countryzones> in the format currently, skip
            // over anything before it.
            findRequiredStartTag(parser, COUNTRY_ZONES_ELEMENT);

            if (processCountryZones(parser, processor) == CountryZonesProcessor.HALT) {
                return;
            }

            // Make sure we are on the </countryzones> tag.
            checkOnEndTag(parser, COUNTRY_ZONES_ELEMENT);

            // Advance to the next tag.
            parser.next();

            // Skip anything until </timezones>, and make sure the file is not truncated and we can
            // find the end.
            consumeUntilEndTag(parser, TIMEZONES_ELEMENT);

            // Make sure we are on the </timezones> tag.
            checkOnEndTag(parser, TIMEZONES_ELEMENT);
        }
    }

    private static boolean processCountryZones(XmlPullParser parser,
            CountryZonesProcessor processor) throws IOException, XmlPullParserException {

        // Skip over any unexpected elements and process <country> elements.
        while (findOptionalStartTag(parser, COUNTRY_ELEMENT)) {
            if (processor == null) {
                consumeUntilEndTag(parser, COUNTRY_ELEMENT);
            } else {
                String code = parser.getAttributeValue(
                        null /* namespace */, COUNTRY_CODE_ATTRIBUTE);
                if (code == null || code.isEmpty()) {
                    throw new XmlPullParserException(
                            "Unable to find country code: " + parser.getPositionDescription());
                }
                String defaultTimeZoneId = parser.getAttributeValue(
                        null /* namespace */, DEFAULT_TIME_ZONE_ID_ATTRIBUTE);
                if (defaultTimeZoneId == null || defaultTimeZoneId.isEmpty()) {
                    throw new XmlPullParserException("Unable to find default time zone ID: "
                            + parser.getPositionDescription());
                }

                String debugInfo = parser.getPositionDescription();
                List<String> timeZoneIds = parseZoneIds(parser);
                if (processor.process(code, defaultTimeZoneId, timeZoneIds, debugInfo)
                        == CountryZonesProcessor.HALT) {
                    return CountryZonesProcessor.HALT;
                }
            }

            // Make sure we are on the </country> element.
            checkOnEndTag(parser, COUNTRY_ELEMENT);
        }

        return CountryZonesProcessor.CONTINUE;
    }

    private static List<String> parseZoneIds(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        List<String> timeZones = new ArrayList<>();

        // Skip over any unexpected elements and process <id> elements.
        while (findOptionalStartTag(parser, ID_ELEMENT)) {
            String zoneIdString = consumeText(parser);

            // Make sure we are on the </id> element.
            checkOnEndTag(parser, ID_ELEMENT);

            // Process the zone ID.
            timeZones.add(zoneIdString);
        }

        // The list is made unmodifiable to avoid callers changing it.
        return Collections.unmodifiableList(timeZones);
    }

    private static void findRequiredStartTag(XmlPullParser parser, String elementName)
            throws IOException, XmlPullParserException {
        findStartTag(parser, elementName, true /* elementRequired */);
    }

    /** Called when on a START_TAG. When returning false, it leaves the parser on the END_TAG. */
    private static boolean findOptionalStartTag(XmlPullParser parser, String elementName)
            throws IOException, XmlPullParserException {
        return findStartTag(parser, elementName, false /* elementRequired */);
    }

    /**
     * Find a START_TAG with the specified name without decreasing the depth, or increasing the
     * depth by more than one. More deeply nested elements and text are skipped, even START_TAGs
     * with matching names. Returns when the START_TAG is found or the next (non-nested) END_TAG is
     * encountered. The return can take the form of an exception or a false if the START_TAG is not
     * found. True is returned when it is.
     */
    private static boolean findStartTag(
            XmlPullParser parser, String elementName, boolean elementRequired)
            throws IOException, XmlPullParserException {

        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            switch (type) {
                case XmlPullParser.START_TAG:
                    String currentElementName = parser.getName();
                    if (elementName.equals(currentElementName)) {
                        return true;
                    }

                    // It was not the START_TAG we were looking for. Consume until the end.
                    parser.next();
                    consumeUntilEndTag(parser, currentElementName);
                    break;
                case XmlPullParser.END_TAG:
                    if (elementRequired) {
                        throw new XmlPullParserException(
                                "No child element found with name " + elementName);
                    }
                    return false;
                default:
                    // Ignore.
                    break;
            }
        }
        throw new XmlPullParserException("Unexpected end of document while looking for "
                + elementName);
    }

    /**
     * Consume the remaining contents of an element and move to the END_TAG. Used when processing
     * within an element can stop. The parser must be pointing at either the END_TAG we are looking
     * for, a TEXT, or a START_TAG nested within the element to be consumed.
     */
    private static void consumeUntilEndTag(XmlPullParser parser, String elementName)
            throws IOException, XmlPullParserException {

        if (parser.getEventType() == XmlPullParser.END_TAG
                && elementName.equals(parser.getName())) {
            // Early return - we are already there.
            return;
        }

        // Keep track of the required depth in case there are nested elements to be consumed.
        // Both the name and the depth must match our expectation to complete.

        int requiredDepth = parser.getDepth();
        // A TEXT tag would be at the same depth as the END_TAG we are looking for.
        if (parser.getEventType() == XmlPullParser.START_TAG) {
            // A START_TAG would have incremented the depth, so we're looking for an END_TAG one
            // higher than the current tag.
            requiredDepth--;
        }

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            int type = parser.next();

            int currentDepth = parser.getDepth();
            if (currentDepth < requiredDepth) {
                throw new XmlPullParserException(
                        "Unexpected depth while looking for end tag: "
                                + parser.getPositionDescription());
            } else if (currentDepth == requiredDepth) {
                if (type == XmlPullParser.END_TAG) {
                    if (elementName.equals(parser.getName())) {
                        return;
                    }
                    throw new XmlPullParserException(
                            "Unexpected eng tag: " + parser.getPositionDescription());
                }
            }
            // Everything else is either a type we are not interested in or is too deep and so is
            // ignored.
        }
        throw new XmlPullParserException("Unexpected end of document");
    }

    /**
     * Reads the text inside the current element. Should be called when the parser is currently
     * on the START_TAG before the TEXT. The parser will be positioned on the END_TAG after this
     * call when it completes successfully.
     */
    private static String consumeText(XmlPullParser parser)
            throws IOException, XmlPullParserException {

        int type = parser.next();
        String text;
        if (type == XmlPullParser.TEXT) {
            text = parser.getText();
        } else {
            throw new XmlPullParserException("Text not found. Found type=" + type
                    + " at " + parser.getPositionDescription());
        }

        type = parser.next();
        if (type != XmlPullParser.END_TAG) {
            throw new XmlPullParserException(
                    "Unexpected nested tag or end of document when expecting text: type=" + type
                            + " at " + parser.getPositionDescription());
        }
        return text;
    }

    private static void checkOnEndTag(XmlPullParser parser, String elementName)
            throws XmlPullParserException {
        if (!(parser.getEventType() == XmlPullParser.END_TAG
                && parser.getName().equals(elementName))) {
            throw new XmlPullParserException(
                    "Unexpected tag encountered: " + parser.getPositionDescription());
        }
    }

    /**
     * Processes &lt;countryzones&gt; data.
     */
    private interface CountryZonesProcessor {

        boolean CONTINUE = true;
        boolean HALT = false;

        /**
         * Returns {@code #CONTINUE} if processing of the XML should continue, {@code HALT} if it
         * should stop (but without considering this an error). Problems with parser are reported as
         * an exception.
         */
        boolean process(String countryIso, String defaultTimeZoneId, List<String> timeZoneIds,
                        String debugInfo) throws XmlPullParserException;
    }

    /**
     * Validates &lt;countryzones&gt; elements. Intended to be used before a proposed installation
     * of new data. To be valid the country ISO code must be normalized, unique, the default time
     * zone ID must be one of the time zones IDs and the time zone IDs list must not be empty. The
     * IDs themselves are not checked against other data to see if they are recognized because other
     * classes will not have been updated with the associated new time zone data yet and so will not
     * be aware of newly added IDs.
     */
    private static class CountryZonesValidator implements CountryZonesProcessor {

        private final Set<String> knownCountryCodes = new HashSet<>();

        @Override
        public boolean process(String countryIso, String defaultTimeZoneId,
                List<String> timeZoneIds, String debugInfo) throws XmlPullParserException {
            if (!normalizeCountryIso(countryIso).equals(countryIso)) {
                throw new XmlPullParserException("Country code: " + countryIso
                        + " is not normalized at " + debugInfo);
            }
            if (knownCountryCodes.contains(countryIso)) {
                throw new XmlPullParserException("Second entry for country code: " + countryIso
                        + " at " + debugInfo);
            }
            if (timeZoneIds.isEmpty()) {
                throw new XmlPullParserException("No time zone IDs for country code: " + countryIso
                        + " at " + debugInfo);
            }
            if (!timeZoneIds.contains(defaultTimeZoneId)) {
                throw new XmlPullParserException("defaultTimeZoneId for country code: "
                        + countryIso + " is not one of the zones " + timeZoneIds + " at "
                        + debugInfo);
            }
            knownCountryCodes.add(countryIso);

            return CONTINUE;
        }
    }

    /**
     * Extracts <em>validated</em> time zones information associated with a specific country code.
     * Processing is halted when the country code is matched and the validated result is also made
     * available via {@link #getValidatedCountryTimeZones()}.
     */
    private static class SelectiveCountryTimeZonesExtractor implements CountryZonesProcessor {

        private final String countryCodeToMatch;
        private CountryTimeZones validatedCountryTimeZones;

        private SelectiveCountryTimeZonesExtractor(String countryCodeToMatch) {
            this.countryCodeToMatch = countryCodeToMatch;
        }

        @Override
        public boolean process(String countryIso, String defaultTimeZoneId,
                List<String> countryTimeZoneIds, String debugInfo) {
            countryIso = normalizeCountryIso(countryIso);
            if (!countryCodeToMatch.equals(countryIso)) {
                return CONTINUE;
            }
            validatedCountryTimeZones = createValidatedCountryTimeZones(countryIso,
                    defaultTimeZoneId, countryTimeZoneIds, debugInfo);

            return HALT;
        }

        /**
         * Returns the CountryTimeZones that matched, or {@code null} if there were no matches.
         */
        CountryTimeZones getValidatedCountryTimeZones() {
            return validatedCountryTimeZones;
        }
    }

    /**
     * A source of Readers that can be used repeatedly.
     */
    private interface ReaderSupplier {
        /** Returns a Reader. Throws an IOException if the Reader cannot be created. */
        Reader get() throws IOException;

        static ReaderSupplier forFile(String fileName, Charset charSet) throws IOException {
            Path file = Paths.get(fileName);
            if (!Files.exists(file)) {
                throw new FileNotFoundException(fileName + " does not exist");
            }
            if (!Files.isRegularFile(file) && Files.isReadable(file)) {
                throw new IOException(fileName + " must be a regular readable file.");
            }
            return () -> Files.newBufferedReader(file, charSet);
        }

        static ReaderSupplier forString(String xml) {
            return () -> new StringReader(xml);
        }
    }

    /**
     * Information about a country's time zones.
     */
    // VisibleForTesting
    public static class CountryTimeZones {
        private final String countryIso;
        private final String defaultTimeZoneId;
        private final List<String> timeZoneIds;

        // Memoized frozen ICU TimeZone objects for the timeZoneIds.
        private List<TimeZone> timeZones;

        public CountryTimeZones(String countryIso, String defaultTimeZoneId,
                List<String> timeZoneIds) {
            this.countryIso = countryIso;
            this.defaultTimeZoneId = defaultTimeZoneId;
            // Create a defensive copy of the IDs list.
            this.timeZoneIds = Collections.unmodifiableList(new ArrayList<>(timeZoneIds));
        }

        public String getCountryIso() {
            return countryIso;
        }

        /**
         * Returns the default time zone ID for a country. Can return null in extreme cases when
         * invalid data is found.
         */
        public String getDefaultTimeZoneId() {
            return defaultTimeZoneId;
        }

        /**
         * Returns an ordered list of time zone IDs for a country in an undefined but "priority"
         * order for a country. The list can be empty if there were no zones configured or the
         * configured zone IDs were not recognized.
         */
        public List<String> getTimeZoneIds() {
            return timeZoneIds;
        }

        /**
         * Returns an ordered list of time zones for a country in an undefined but "priority"
         * order for a country. The list can be empty if there were no zones configured or the
         * configured zone IDs were not recognized.
         */
        public synchronized List<TimeZone> getTimeZones() {
            if (timeZones == null) {
                ArrayList<TimeZone> mutableList = new ArrayList<>(timeZoneIds.size());
                for (String timeZoneId : timeZoneIds) {
                    TimeZone timeZone = getValidFrozenTimeZoneOrNull(timeZoneId);
                    // This shouldn't happen given the validation that takes place in
                    // createValidatedCountryTimeZones().
                    if (timeZone == null) {
                        System.logW("Skipping invalid zone: " + timeZoneId);
                        continue;
                    }
                    mutableList.add(timeZone);
                }
                timeZones = Collections.unmodifiableList(mutableList);
            }
            return timeZones;
        }

        private static TimeZone getValidFrozenTimeZoneOrNull(String timeZoneId) {
            TimeZone timeZone = TimeZone.getFrozenTimeZone(timeZoneId);
            if (timeZone.getID().equals(TimeZone.UNKNOWN_ZONE_ID)) {
                return null;
            }
            return timeZone;
        }
    }

    private static String normalizeCountryIso(String countryIso) {
        // Lowercase ASCII is normalized for the purposes of the input files and the code in this
        // class.
        return countryIso.toLowerCase(Locale.US);
    }

    // VisibleForTesting
    public static CountryTimeZones createValidatedCountryTimeZones(String countryIso,
            String defaultTimeZoneId, List<String> countryTimeZoneIds, String debugInfo) {

        // We rely on ZoneInfoDB to tell us what the known valid time zone IDs are. ICU may
        // recognize more but we want to be sure that zone IDs can be used with java.util as well as
        // android.icu and ICU is expected to have a superset.
        String[] validTimeZoneIdsArray = ZoneInfoDB.getInstance().getAvailableIDs();
        HashSet<String> validTimeZoneIdsSet = new HashSet<>(Arrays.asList(validTimeZoneIdsArray));
        List<String> validCountryTimeZoneIds = new ArrayList<>();
        for (String countryTimeZoneId : countryTimeZoneIds) {
            if (!validTimeZoneIdsSet.contains(countryTimeZoneId)) {
                System.logW("Skipping invalid zone: " + countryTimeZoneId + " at " + debugInfo);
            } else {
                validCountryTimeZoneIds.add(countryTimeZoneId);
            }
        }

        // We don't get too strict at runtime about whether the defaultTimeZoneId must be
        // one of the country's time zones because this is the data we have to use (we also
        // assume the data was validated by earlier steps). The default time zone ID must just
        // be a recognized zone ID: if it's not valid we leave it null.
        if (!validTimeZoneIdsSet.contains(defaultTimeZoneId)) {
            System.logW("Invalid default time zone ID: " + defaultTimeZoneId
                    + " at " + debugInfo);
            defaultTimeZoneId = null;
        }

        return new CountryTimeZones(countryIso, defaultTimeZoneId, validCountryTimeZoneIds);
    }
}
