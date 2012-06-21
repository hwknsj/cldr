package org.unicode.cldr.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unicode.cldr.util.Builder;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.LanguageTagParser;
import org.unicode.cldr.util.LocaleIDParser;
import org.unicode.cldr.util.RegexLookup;
import org.unicode.cldr.util.RegexUtilities;
import org.unicode.cldr.util.SupplementalDataInfo;
import org.unicode.cldr.util.CldrUtility.Output;
import org.unicode.cldr.util.RegexLookup.Finder;
import org.unicode.cldr.util.SupplementalDataInfo.MeasurementType;

import com.ibm.icu.dev.test.util.CollectionUtilities;

/**
 * Converts CLDR locale XML files to a format suitable for writing ICU data
 * with.
 * @author jchye
 */
public class LdmlLocaleMapper extends LdmlMapper {
    /**
     * Map for converting enums to their integer values.
     */
    private static final Map<String,String> enumMap = Builder.with(new HashMap<String,String>())
            .put("titlecase-firstword", "1").freeze();

    private static final Pattern DRAFT_PATTERN = Pattern.compile("\\[@draft=\"\\w+\"]");
    private static final Pattern TERRITORY_XPATH = Pattern.compile(
            "//ldml/localeDisplayNames/territories/territory\\[@type=\"(\\w+)\"]");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\$Revision:\\s*(\\d+)\\s*\\$");
    private static final Pattern RB_DATETIMEPATTERN = Pattern.compile(
            "/calendar/(\\w++)/DateTimePatterns");

    private SupplementalDataInfo supplementalDataInfo;
    private Factory factory;
    private Factory specialFactory;

    private Set<String> deprecatedTerritories;

    /**
     * Special hack comparator, so that RB strings come out in the right order.
     * This is only important for the order of items in arrays.
     */
    private static Comparator<CldrValue> SpecialLdmlComparator = new Comparator<CldrValue>() {
        private final Pattern CURRENCY_FORMAT = Pattern.compile(
            "//ldml/numbers/currencies/currency\\[@type=\"\\w++\"]/(.++)");
        private final Pattern DATE_OR_TIME_FORMAT = Pattern.compile(
                "//ldml/dates/calendars/calendar\\[@type=\"\\w++\"]/(date|time)Formats/.*");
        private final Pattern MONTH_PATTERN = Pattern.compile(
                "//ldml/dates/calendars/calendar\\[@type=\"\\w++\"]/months/monthContext\\[@type=\"[\\w\\-]++\"]/monthWidth\\[@type=\"\\w++\"]/month\\[@type=\"\\d++\"](\\[@yeartype=\"leap\"])?");
        private final Pattern CONTEXT_TRANSFORM = Pattern.compile(
                "//ldml/contextTransforms/contextTransformUsage\\[@type=\"([^\"]++)\"]/contextTransform\\[@type=\"([^\"]++)\"]");

        /**
         * Reverse the ordering of the following:
         * //ldml/numbers/currencies/currency[@type="([^"]*)"]/displayName ; curr ; /Currencies/$1
         * //ldml/numbers/currencies/currency[@type="([^"]*)"]/symbol ; curr ; /Currencies/$1
         * and the following (time/date)
         * //ldml/dates/calendars/calendar[@type="([^"]*)"]/(dateFormats|dateTimeFormats|timeFormats)/(?:[^/\[]*)[@type="([^"]*)"]/(?:[^/\[]*)[@type="([^"]*)"]/.* ; locales ; /calendar/$1/DateTimePatterns
         */
        @SuppressWarnings("unchecked")
        @Override
        public int compare(CldrValue value0, CldrValue value1) {
            String arg0 = value0.getXpath();
            String arg1 = value1.getXpath();

            Matcher[] matchers = new Matcher[2];
            if (matches(CURRENCY_FORMAT, arg0, arg1, matchers)) {
                // Use ldml ordering except that symbol should be first.
                if (matchers[0].group(1).equals("symbol")) {
                    return -1;
                } else if (matchers[1].group(1).equals("symbol")) {
                    return 1;
                }
            } else if (matches(DATE_OR_TIME_FORMAT, arg0, arg1, matchers)) {
                int compareValue = matchers[0].group(1).compareTo(matchers[1].group(1));
                if (compareValue != 0) return -compareValue;
            } else if (matches(CONTEXT_TRANSFORM, arg0, arg1, matchers)) {
                // Sort uiListOrMenu before stand-alone.
                if (matchers[0].group(1).equals(matchers[1].group(1))) {
                    return -matchers[0].group(2).compareTo(matchers[1].group(2));
                }
            } else if (matches(MONTH_PATTERN, arg0, arg1, matchers)) {
                // Sort leap year types after normal month types.
                String matchGroup0 = matchers[0].group(1);
                String matchGroup1 = matchers[1].group(1);
                if (matchGroup0 != matchGroup1) {
                    return matchGroup0 == null && matchGroup1 != null ? -1 : 1;
                }
            }

            return CLDRFile.ldmlComparator.compare(arg0, arg1);
        }
    };
    
    public LdmlLocaleMapper(Factory factory, Factory specialFactory,
            SupplementalDataInfo supplementalDataInfo) {
        super("ldml2icu.txt");
        this.factory = factory;
        this.specialFactory = specialFactory;
        this.supplementalDataInfo = supplementalDataInfo;
    }

    /**
     * @return the set of locales available for processing by this mapper
     */
    public Set<String> getAvailable() {
        return factory.getAvailable();
    }
    
    private boolean hasSpecialFile(String filename) {
        return specialFactory != null && specialFactory.getAvailable().contains(filename);
    }

    /**
     * @return the set of deprecated territories to be ignored. Remove when no longer
     * present in CLDR data.
     */
    private Set<String> getDeprecatedTerritories() {
        if (deprecatedTerritories == null) {
            deprecatedTerritories = Builder.with(
                supplementalDataInfo.getLocaleAliasInfo().get("territory").keySet())
                .remove("062").remove("172").remove("200").remove("830")
                .remove("AN").remove("CS").remove("QU").get();
        }
        return deprecatedTerritories;
    }

    /**
     * Fills an IcuData object using the CLDR data for the specified locale.
     * @param locale
     * @return the filled IcuData object
     */
    public IcuData fillFromCLDR(String locale) {
        Set<String> deprecatedTerritories = getDeprecatedTerritories();
        RegexLookup<RegexResult> pathConverter = getPathConverter();

        // First pass through the unresolved CLDRFile to get all icu paths.
        CLDRFile cldr = factory.make(locale, false);
        Map<String,List<CldrValue>> pathValueMap = new HashMap<String,List<CldrValue>>();
        Set<String> validRbPaths = new HashSet<String>();
        for (String xpath : cldr) {
            // Territory hacks to be removed once CLDR data is fixed.
            Matcher matcher = TERRITORY_XPATH.matcher(xpath);
            if (matcher.matches()) {
                String country = matcher.group(1);
                if (deprecatedTerritories.contains(country)) {
                    continue;
                }
            }

            // Add rb paths.
            Output<Finder> matcherFound = new Output<Finder>();
            RegexResult regexResult = matchXPath(pathConverter, cldr, xpath, matcherFound);
            if (regexResult == null) continue;
            String[] arguments = matcherFound.value.getInfo();
            for (PathValueInfo info : regexResult) {
                String rbPath = info.processRbPath(arguments);
                validRbPaths.add(rbPath);
                // The immediate parent of every path should also exist.
                validRbPaths.add(rbPath.substring(0, rbPath.lastIndexOf('/')));
            }
        }
        
        // Get all values from the resolved CLDRFile.
        CLDRFile resolvedCldr = factory.make(locale, true);
        Set<String> resolvedPaths = new HashSet<String>();
        CollectionUtilities.addAll(resolvedCldr.iterator(), resolvedPaths);
        //resolvedPaths.addAll(LdmlMapper.getFallbackPaths().keySet());
        for (String xpath : resolvedPaths) {
            addMatchesForPath(xpath, resolvedCldr, validRbPaths, pathValueMap);
        }

        // Add fallback paths if necessary.
        addFallbackValues(pathValueMap);

        // Add special values to file.
        IcuData icuData = new IcuData("common/main/" + locale + ".xml", locale, true, enumMap);
        if (hasSpecialFile(locale)) {
            icuData.setHasSpecial(true);
            CLDRFile specialCldrFile = specialFactory.make(locale, false);
            for (String xpath : specialCldrFile) {
                addMatchesForPath(xpath, specialCldrFile, null, pathValueMap);
            }
        }

        // Convert values to final data structure.
        for (String rbPath : pathValueMap.keySet()) {
            List<CldrValue> values = pathValueMap.get(rbPath);

            // HACK: DateTimePatterns needs a duplicate of the medium
            // dateTimeFormat (formerly indicated using dateTimeFormats/default).
            // This hack can be removed when ICU no longer requires it.
            Matcher matcher = RB_DATETIMEPATTERN.matcher(rbPath);
            if (matcher.matches()) {
                String calendar = matcher.group(1);
                List<CldrValue> valueList = getList("/calendar/" + calendar + "/DateTimePatterns", pathValueMap);
                // Create a dummy xpath to sort the value in front of the other date time formats.
                String basePath = "//ldml/dates/calendars/calendar[@type=\"" + calendar + "\"]/dateTimeFormats";
                String mediumFormatPath = basePath + "/dateTimeFormatLength[@type=\"medium\"]/dateTimeFormat[@type=\"standard\"]/pattern[@type=\"standard\"]";
                valueList.add(new CldrValue(basePath,
                        getStringValue(resolvedCldr, mediumFormatPath),
                        false));
            }

            Collections.sort(values, SpecialLdmlComparator);
            List<String[]> sortedValues = new ArrayList<String[]>();
            List<String> arrayValues = new ArrayList<String>();
            String lastPath = values.get(0).getXpath();
            // Group isArray for the same xpath together.
            for (CldrValue value : values) {
                String currentPath = value.getXpath();
                if (!currentPath.equals(lastPath) || 
                        !value.isArray() && arrayValues.size() != 0) {
                    sortedValues.add(toArray(arrayValues));
                    arrayValues.clear();
                }
                lastPath = currentPath;
                arrayValues.add(value.getValue());
            }
            sortedValues.add(toArray(arrayValues));
            icuData.addAll(rbPath, sortedValues);
        }
        // Hacks
        hackAddExtras(resolvedCldr, locale, icuData);
        return icuData;
    }

    /**
     * Converts a list into an Array.
     */
    private static String[] toArray(List<String> list) {
        String[] array = new String[list.size()];
        list.toArray(array);
        return array;
    }

    public static String getFullXPath(String xpath, CLDRFile cldrFile) {
        String fullPath = cldrFile.getFullXPath(xpath);
        return fullPath == null ? xpath : DRAFT_PATTERN.matcher(fullPath).replaceAll("");
    }

    /**
     * @param cldr
     * @param path
     * @param matcherFound
     * @return the result of converting an xpath into an ICU-style path
     */
    private static RegexResult matchXPath(RegexLookup<RegexResult> lookup,
            CLDRFile cldr, String path,
            Output<Finder> matcherFound) {
        String fullPath = cldr.getFullXPath(path);
        fullPath = fullPath == null ? path : DRAFT_PATTERN.matcher(fullPath).replaceAll("");
        RegexResult result = lookup.get(fullPath, null, null, matcherFound, null);
        return result;
    }

    /**
     * Attempts to match an xpath and adds the results of a successful match to
     * the specified map
     * @param xpath the xpath to be matched
     * @param cldrFile the CLDR file to get locale data from
     * @param validRbPaths the set of valid rbPaths that the result must belong
     *        to, null if such a requirement does not exist
     * @param pathValueMap the map that the results will be added to
     */
    private void addMatchesForPath(String xpath, CLDRFile cldrFile,
            Set<String> validRbPaths, Map<String, List<CldrValue>> pathValueMap) {
        Output<Finder> matcher = new Output<Finder>();
        RegexResult regexResult = matchXPath(getPathConverter(),
            cldrFile, xpath, matcher);
        if (regexResult == null) return;
        String[] arguments = matcher.value.getInfo();
        if (!regexResult.argumentsMatch(cldrFile, arguments)) return;
        for (PathValueInfo info : regexResult) {
            // TODO: make localeMapper and supplementalMapper calls to processX consistent!
            String rbPath = info.processRbPath(arguments);
            // Don't add additional paths at this stage.
            if (validRbPaths != null && !validRbPaths.contains(rbPath)) continue;
            List<CldrValue> valueList = getList(rbPath, pathValueMap);
            String[] values = info.processValues(arguments, cldrFile, xpath);
            for (String value : values) {
                valueList.add(new CldrValue(xpath, value, info.isArray()));
            }
        }
    }
    
    private List<CldrValue> getList(String key, Map<String, List<CldrValue>> pathValueMap) {
        List<CldrValue> list = pathValueMap.get(key);
        if (list == null) {
            list = new ArrayList<CldrValue>();
            pathValueMap.put(key, list);
        }
        return list;
    }

    /**
     * Adds all mappings that couldn't be represented in the ldml2icu.txt file.
     * @param cldrResolved
     * @param locale
     */
    private void hackAddExtras(CLDRFile cldrResolved, String locale, IcuData icuData) {
        // Specify parent of non-language locales.
        String parent = supplementalDataInfo.getExplicitParentLocale(locale);
        if (parent != null) {
            icuData.add("/%%Parent", parent);
        }
        
        // <version number="$Revision: 5806 $"/>
        String versionPath = cldrResolved.getFullXPath("//ldml/identity/version");
        Matcher versionMatcher = VERSION_PATTERN.matcher(versionPath);
        if (!versionMatcher.find()) {
            int failPoint = RegexUtilities.findMismatch(versionMatcher, versionPath);
            String show = versionPath.substring(0, failPoint) + "☹" + versionPath.substring(failPoint);
            throw new IllegalArgumentException("no version match with: " + show);
        }
        int versionNum = Integer.parseInt(versionMatcher.group(1));
        String versionValue = "2.0." + (versionNum / 100) + "." + (versionNum % 100);
        icuData.add("/Version", versionValue);

        // PaperSize:intvector{ 279, 216, }
        String localeID = cldrResolved.getLocaleID();
        String path = "/PaperSize:intvector";
        String paperType = getMeasurementToDisplay(localeID, MeasurementType.paperSize);
        if (paperType == null) {
            // do nothing
        } else if (paperType.equals("A4")) {
            icuData.add(path, new String[]{"297", "210"});
        } else if (paperType.equals("US-Letter")) {
            icuData.add(path, new String[]{"279", "216"});
        } else {
            throw new IllegalArgumentException("Unknown paper type");
        }

        // MeasurementSystem:int{1}
        path = "/MeasurementSystem:int";
        String measurementSystem = getMeasurementToDisplay(localeID, MeasurementType.measurementSystem);
        if (measurementSystem == null) {
            // do nothing
        } else if (measurementSystem.equals("metric")) {
            icuData.add(path, "0");
        } else if (measurementSystem.equals("US")) {
            icuData.add(path, "1");
        } else {
            throw new IllegalArgumentException("Unknown measurement system");
        }
    }
    
    /**
     * Returns the measurement to be displayed for the specified locale and
     * measurement type. Measurements should not be displayed if the immediate
     * parent of the locale has the same measurement as the locale.
     * @param localeID
     * @param measurementType
     * @return the measurement to be displayed, or null if it should not be displayed
     */
    private String getMeasurementToDisplay(String localeID, MeasurementType measurementType) {
        String type = getMeasurement(localeID, measurementType);
        if (type == null) return null;
        // Don't add type if a parent has the same value for that type.
        String parent = LocaleIDParser.getParent(localeID);
        String parentType = null;
        while (parentType == null && parent != null) {
            parentType = getMeasurement(parent, measurementType);
            parent = LocaleIDParser.getParent(parent);
        }
        return type.equals(parentType) ? null : type;
    }

    /**
     * @param localeID
     * @param measurementType the type of measurement required
     * @return the measurement of the specified locale
     */
    private String getMeasurement(String localeID, MeasurementType measurementType) {
        String region = localeID.equals("root") ? "001" : new LanguageTagParser().set(localeID).getRegion();
        Map<MeasurementType, Map<String, String>> regionMeasurementData = supplementalDataInfo.getTerritoryMeasurementData();
        Map<String, String> typeMap = regionMeasurementData.get(measurementType);
        return typeMap.get(region);
    }

}
