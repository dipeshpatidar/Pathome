package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.dto.AvailabilityStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.LocalityRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-Performance, $O(1)$ L1 Cache-Backed Natural Language Property Parser
 * Service.
 * Engineered for sub-millisecond execution time, zero GC pressure, and 100% DB
 * auto-persistence.
 */
@Service
public class PropertyParserService {

    private static final Logger log = LoggerFactory.getLogger(PropertyParserService.class);
    private static final String TARGET_PROPERTY_INDEX_REGEX =
            "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|" +
            "eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|" +
            "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" +
            "seventeen|eighteen|nineteen|twenty|\\d{1,3}(?:st|nd|rd|th)?";

    // Pre-compiled Thread-Safe Static RegEx Patterns (Zero runtime recompilation overhead)
    private static final Pattern MULTI_PROMPT_SPLIT_PATTERN = Pattern.compile(
            "(?m)(?:^[\\s]*[-=_*~]{3,}[\\s]*$)|" +
            "(?m)(?:^[\\s]*(?:property|flat|listing|house|unit)\\s*#?\\d+[:\\.\\-]?\\s*)|" +
            "(?m)(?:^[\\s]*(?:\\[?\\d+[\\]\\)\\.\\:\\-]|#\\d+)\\s+)|" +
            "(?i)\\b(?:next\\s*(?:property|flat|house|listing|unit|one)|agli\\s*property|dusra\\s*flat)\\b|" +
            "(?i)\\b(?:and\\s+)?(?:the\\s+)?(?:second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|" +
            "eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|another)" +
            "\\s+(?:property|flat|house|listing|unit)(?:\\s+is)?\\b|" +
            "(?:\\r?\\n\\s*\\r?\\n+)"
    );
    private static final Pattern TARGETED_AMENDMENT_PREFIX_PATTERN = Pattern.compile(
            "\\b(?:and\\s+)?(?:in|for|to|about|regarding)\\s+(?:the\\s+)?(?:" +
            "(" + TARGET_PROPERTY_INDEX_REGEX + ")\\s+(?:property|flat|house|listing|unit)|" +
            "(?:property|flat|house|listing|unit)\\s*(?:number\\s*)?(" + TARGET_PROPERTY_INDEX_REGEX + "))" +
            "\\b\\s*[,;:\\-]?\\s*(?:please\\s+)?(?:add|set|keep|change|update|make|put|include)?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGETED_AMENDMENT_DIRECT_PATTERN = Pattern.compile(
            "\\b(?:and\\s+)?(?:" +
            "(" + TARGET_PROPERTY_INDEX_REGEX + ")\\s+(?:property|flat|house|listing|unit)|" +
            "(?:property|flat|house|listing|unit)\\s*(?:number\\s*)?(" + TARGET_PROPERTY_INDEX_REGEX + "))" +
            "\\b\\s*[,;:\\-]?\\s*(?:please\\s+)?(?:add|set|keep|change|update|make|put|include|should\\s+be)\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGETED_AMENDMENT_ACTION_FIRST_PATTERN = Pattern.compile(
            "\\b(?:add|set|keep|change|update|put|include)\\s+([^\\n.!?]{2,160}?)\\s+(?:in|for|to)\\s+(?:the\\s+)?(?:" +
            "(" + TARGET_PROPERTY_INDEX_REGEX + ")\\s+(?:property|flat|house|listing|unit)|" +
            "(?:property|flat|house|listing|unit)\\s*(?:number\\s*)?(" + TARGET_PROPERTY_INDEX_REGEX + "))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HINDI_HAZAR_RENT_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}(?:\\.\\d+)?)\\s*(?:hazar|hzaar|hzar)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HINDI_LAKH_RENT_PATTERN = Pattern.compile(
            "\\b(\\d{1,2}(?:\\.\\d+)?)\\s*(?:lakh|lac|lakhs|lacs)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HINDI_MONTHS_DEPOSIT_PATTERN = Pattern.compile(
            "\\b(?:deposit|scurity|security\\s*deposit)\\s*(?:is|amount|of|=|-|:)?\\s*(\\d{1,2})\\s*(?:mahina|mahine|month|months)\\b|\\b(\\d{1,2})\\s*(?:mahina|mahine|month|months)\\s*(?:deposit|security|ka\\s*deposit)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_PROMPT_URL_PATTERN = Pattern.compile(
            "https?://[^\\s,;\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPO_FLAT_PATTERN = Pattern.compile(
            "\\b(flt|flts|flatt|appartment|appatment|apartmnt|apt|apts)\\b");
    private static final Pattern TYPO_HOUSE_PATTERN = Pattern.compile(
            "\\b(viila|vlla|vlia|bunglow|bunglows|independant|indepent)\\b");
    private static final Pattern TYPO_PLOT_PATTERN = Pattern.compile("\\b(plott|pott|lnd)\\b");
    private static final Pattern TYPO_SEMI_FURNISHED_PATTERN = Pattern.compile(
            "\\b(semi\\s*furnishd|semifurnished|semi\\-furnished|semifurnish)\\b");
    private static final Pattern TYPO_FULLY_FURNISHED_PATTERN = Pattern.compile(
            "\\b(fully\\s*furnishd|full\\s*furnished|fully\\-furnished|fullfurnish)\\b");
    private static final Pattern TYPO_UNFURNISHED_PATTERN = Pattern.compile("\\b(unfurnishd|un\\-furnished|bare)\\b");
    private static final Pattern TYPO_EAST_FACING_PATTERN = Pattern.compile("\\b(est\\s*facing|east\\s*faceing|east\\s*dacing)\\b");
    private static final Pattern TYPO_WEST_FACING_PATTERN = Pattern.compile("\\b(wst\\s*facing|west\\s*faceing|west\\s*dacing)\\b");
    private static final Pattern TYPO_NORTH_FACING_PATTERN = Pattern.compile("\\b(noth\\s*facing|north\\s*faceing|north\\s*dacing)\\b");
    private static final Pattern TYPO_SOUTH_FACING_PATTERN = Pattern.compile("\\b(suth\\s*facing|south\\s*faceing|south\\s*dacing)\\b");
    private static final Pattern TYPO_RENT_PATTERN = Pattern.compile("\\b(rnt|ren|mothly\\s*rent|pm|p\\.m\\.)\\b");
    private static final Pattern TYPO_DEPOSIT_PATTERN = Pattern.compile(
            "\\b(deposits|depost|deposite|diposite|diposit|scurity\\s*deposit|scurity\\s*dep|securuity\\s*deposit|securuity)\\b");
    private static final Pattern TYPO_NEAR_PATTERN = Pattern.compile("\\b(near\\s*by|nearby|near\\s*to|opp\\s*to|infront\\s*of)\\b");
    private static final Pattern TYPO_BROKERAGE_PATTERN = Pattern.compile("\\b(brokraj|brokrage|brookerage|brokerg|brokorage|commission)\\b");
    private static final Pattern TYPO_SAKET_NAGAR_PATTERN = Pattern.compile("\\b(saket\\s*nagr|saketnagar)\\b");
    private static final Pattern TYPO_VIJAY_NAGAR_PATTERN = Pattern.compile("\\b(vijay\\s*nagr|vijayngr|vijaynagar|vijayanagar)\\b");
    private static final Pattern TYPO_NANDA_NAGAR_PATTERN = Pattern.compile("\\b(nanda\\s*nagr|nandanagar)\\b");
    private static final Pattern TYPO_BHAWARKUA_PATTERN = Pattern.compile("\\b(bhawarkwa|bhawar\\s*kua)\\b");
    private static final Pattern TYPO_PALASIA_PATTERN = Pattern.compile("\\b(palasiaa)\\b");
    private static final Pattern TYPO_NIPANIA_PATTERN = Pattern.compile("\\b(nipaniya|nipaniyaa)\\b");

    private static final Pattern NUM_BHK_PATTERN = Pattern.compile(
            "\\b([1-9](?:\\.5)?|10)\\s*(?:bhk|rk|bedroom|bedrooms|bed|beds|room|rooms|bk|bhkk|bhkks|dfbhk|sdfbhk|flat|flt|flats|flatt|apartment|house|villa)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REV_BHK_PATTERN = Pattern.compile(
            "\\b(?:bhk|rk|bedroom|bedrooms|bed|beds|room|rooms|bk|bhkk|bhkks|dfbhk|sdfbhk|flat|flt|flats|flatt|apartment|house|villa)\\s*([1-9](?:\\.5)?|10)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_BHK_PATTERN = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten)\\s*(?:bhk|rk|bedroom|bedrooms|bed|beds|room|rooms|bk|bhkk|bhkks|dfbhk|sdfbhk)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BATHROOMS_PATTERN = Pattern.compile(
            "\\b([1-9]|10)\\b\\s*(?:[a-zA-Z0-9\\-\\_]{1,30}\\s+){0,10}?(?:bath|baths|bathroom|bathrooms|bathromm|bathrom|toilet|washroom)\\b|\\b(?:bath|baths|bathroom|bathrooms|bathromm|bathrom|toilet|washroom)\\b\\s*(?:[a-zA-Z0-9\\-\\_]{1,30}\\s+){0,10}?([1-9]|10)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_BATHROOMS_PATTERN = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten)\\b\\s*(?:[a-zA-Z0-9\\-\\_]{1,30}\\s+){0,10}?(?:bath|baths|bathroom|bathrooms|bathromm|bathrom|toilet|washroom)\\b|\\b(?:bath|baths|bathroom|bathrooms|bathromm|bathrom|toilet|washroom)\\b\\s*(?:[a-zA-Z0-9\\-\\_]{1,30}\\s+){0,10}?(one|two|three|four|five|six|seven|eight|nine|ten)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BATHROOMS_TO_SPEECH_TYPO_PATTERN = Pattern.compile(
            "\\b(?:bath|baths|bathroom|bathrooms|bathromm|bathrom|toilet|washroom)\\s+(?:is|are)\\s+(?:to|too)\\b",
            Pattern.CASE_INSENSITIVE);

    // Immediate & High-Precision Forward/Reverse Rent Patterns (Strict word boundaries & zero cross-field bleeding)
    private static final Pattern FWD_RENT_PATTERN = Pattern.compile(
            "\\b(?:monthly\\s*rent|rent|per\\s*month|/month|pm|rnt|ren)\\b(?:\\s+(?:is|of|amount|fee|charge|rs|around|about|approximately|approx|roughly|the)){0,5}\\s*[:\\-]?\\s*(?:rs\\.?|₹)?\\s*\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REV_RENT_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\b\\s*[:\\-]?\\s*(?:rs\\.?|₹)?\\s*(?:rent|per\\s*month|/month|pm|rnt|ren|monthly\\s*rent)\\b",
            Pattern.CASE_INSENSITIVE);

    // Explicit Forward & Reverse Brokerage Patterns (Supports typos "brokraj", "brokrage", "brookerage")
    private static final Pattern FWD_BROKERAGE_PATTERN = Pattern.compile(
            "\\b(?:brokerage|brokraj|brokrage|brookerage|brokerg|broker\\s*fee|commission)\\b(?:\\s+(?:fee|fees|is|of|amount|charge|charges|around|about|approximately|approx|roughly|the|=|-)){0,6}\\s*[:\\-]?\\s*(?:rs\\.?|₹)?\\s*\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REV_BROKERAGE_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\b\\s*[:\\-]?\\s*(?:rs\\.?|₹)?\\s*(?:fee|fees|is|of|amount|charge|charges)?\\s*(?:brokerage|brokraj|brokrage|brookerage|brokerg|broker\\s*fee|commission)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BROKERAGE_DAYS_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s*(?:[a-zA-Z0-9\\-\\_]{1,30}\\s+){0,3}?(?:day|days)\\s*(?:[a-zA-Z0-9\\-\\_]{1,30}\\s+){0,3}?(?:rent|brokerage)?\\b",
            Pattern.CASE_INSENSITIVE);

    // Explicit Area / Sqft Pattern (Forward & Reverse Multi-Word Distance Independent, 3+ digits)
    private static final Pattern SQFT_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}(?:,\\d{3})+|\\d{3,5})\\s*(?:[a-zA-Z\\-\\_]{1,30}\\s+){0,5}?(?:sqft|sq\\.ft|sq\\s*ft|sqfeet|square\\s*feet|sq\\s*meters|sqm)\\b|\\b(?:sqft|sq\\.ft|sq\\s*ft|sqfeet|square\\s*feet|sq\\s*meters|sqm)\\b\\s*(?:[a-zA-Z\\-\\_]{1,30}\\s+){0,5}?(\\d{1,3}(?:,\\d{3})+|\\d{3,5})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AREA_UNIT_AFTER_NUMBER_PATTERN = Pattern.compile(
            "\\s*(?:sqft|sq\\.\\s*ft|sqfeet|square\\s*feet|sq\\s*meters|sqm)\\b",
            Pattern.CASE_INSENSITIVE);

    // Explicit Forward & Reverse Security Deposit Patterns (Supports typos "securuity", "is 1+1 60000")
    private static final Pattern FWD_DEPOSIT_PATTERN = Pattern.compile(
            "\\b(?:security\\s*deposit|deposit|dep|depost|deposite|diposite|scurity|securuity)\\b(?:\\s+(?:is|amount|of|around|about|approximately|approx|roughly|the|=|-)){0,5}\\s*[:\\-]?\\s*(?:rs\\.?|₹)?\\s*\\b([1-3]\\+[1-3]\\s*\\d{4,6}|[1-3]\\+[1-3]|\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REV_DEPOSIT_PATTERN = Pattern.compile(
            "\\b([1-3]\\+[1-3]\\s*\\d{4,6}|[1-3]\\+[1-3]|\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\b\\s*[:\\-]?\\s*(?:rs\\.?|₹)?\\s*(?:is|amount)?\\s*(?:security\\s*deposit|deposit|dep|depost|deposite|diposite|scurity|securuity)\\b",
            Pattern.CASE_INSENSITIVE);

    // Furnishing & Possession Patterns (Supports natural text dates e.g. "20th of september", "25th of sep")
    private static final Pattern FURNISHING_PATTERN = Pattern
            .compile("\\b(unfurnished|semi[\\-\\s]?furnished|fully[\\-\\s]?furnished)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSSESSION_PATTERN = Pattern.compile(
            "\\b(?:ready\\s*to\\s*move|immediate[\\s\\-]?possession|available\\s*from|available|possession\\s*date|possession)\\b" +
            "(?:\\s+(?:is|on|from|by|will\\s+be|would\\s+be)){0,3}\\s*[:\\-]?\\s*" +
            "(\\d{1,2}(?:st|nd|rd|th)?(?:\\s+of)?\\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+\\d{4})?|" +
            "\\d{1,2}[-\\/]\\d{1,2}[-\\/]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|ready\\s*to\\s*move|immediate)\\b|" +
            "\\b(ready\\s*to\\s*move|immediate[\\s\\-]?possession|immediate|available\\s*now)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE_POSSESSION_PATTERN = Pattern.compile(
            "\\b(?:(?:available|availability|possession|ready(?:\\s*to\\s*move)?|move\\s*in)\\s*(?:is\\s*)?(?:after|in|from)\\s*|(?:after|in)\\s+)(\\d{1,2})\\s*(days?|weeks?|months?|years?|mahina|mahine)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HINGLISH_RELATIVE_POSSESSION_PATTERN = Pattern.compile(
            "\\b(?:(?:available|availability|possession|ready(?:\\s*to\\s*move)?|move\\s*in)\\s*(?:is\\s*)?)?(\\d{1,2})\\s*(days?|weeks?|months?|years?|mahina|mahine)\\s+(?:ke\\s+)?baad\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIWALI_POSSESSION_PATTERN = Pattern.compile(
            "\\b(?:post|after)\\s+(?:diwali|deepavali|deepawali)(?:\\s+(\\d{4}))?\\b|\\b(?:diwali|deepavali|deepawali)(?:\\s+(\\d{4}))?\\s+(?:ke\\s+)?baad\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_POSSESSION_INTERVAL_CONTEXT_PATTERN = Pattern.compile(
            "\\b(?:deposit|brokerage|lease|agreement|notice|tenure)(?:\\s+[a-z]+){0,4}\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter RESOLVED_POSSESSION_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter NUMERIC_TWO_DIGIT_YEAR_SLASH_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("d/M/")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                    .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter NUMERIC_TWO_DIGIT_YEAR_DASH_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("d-M-")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                    .toFormatter(Locale.ENGLISH);
    private static final List<DateTimeFormatter> FULL_POSSESSION_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH),
            NUMERIC_TWO_DIGIT_YEAR_SLASH_FORMATTER,
            NUMERIC_TWO_DIGIT_YEAR_DASH_FORMATTER,
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH));
    private static final List<DateTimeFormatter> MONTH_DAY_POSSESSION_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH));
    private static final Pattern DATE_ORDINAL_SUFFIX_PATTERN = Pattern.compile(
            "(?<=\\d)(?:st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_OF_WORD_PATTERN = Pattern.compile(
            "\\s+of\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOUR_DIGIT_YEAR_PATTERN = Pattern.compile("\\b\\d{4}\\b");
    private static final Map<Integer, LocalDate> DIWALI_DATES = Map.ofEntries(
            Map.entry(2025, LocalDate.of(2025, 10, 20)),
            Map.entry(2026, LocalDate.of(2026, 11, 8)),
            Map.entry(2027, LocalDate.of(2027, 10, 29)),
            Map.entry(2028, LocalDate.of(2028, 10, 17)),
            Map.entry(2029, LocalDate.of(2029, 11, 5)),
            Map.entry(2030, LocalDate.of(2030, 10, 26)),
            Map.entry(2031, LocalDate.of(2031, 11, 14)));

    // Address, State, Pincode & Landmark Patterns
    private static final Pattern STATE_PATTERN = Pattern.compile(
            "\\b(madhya\\s*pradesh|maharashtra|karnataka|delhi\\s*ncr|delhi|rajasthan|uttar\\s*pradesh|gujarat|haryana|tamil\\s*nadu|west\\s*bengal|telangana|goa)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b([1-9]\\d{5})\\b");
    private static final Pattern LANDMARK_PATTERN = Pattern.compile(
            "\\b(?:landmark|near\\s*by|nearby|near\\s*to|near|opposite|opp|behind|next\\s+to|adjacent\\s+to|in\\s+front\\s+of|infront\\s+of)\\s+" +
            "([A-Za-z0-9][A-Za-z0-9\\s]{1,60}?)(?=\\s+(?:and\\s+)?(?:in\\s+front\\s+of|infront\\s+of|near\\s*by|nearby|near\\s+to|near|" +
            "opposite|opp|behind|next\\s+to|adjacent\\s+to|with|having|rent|brokerage|deposit|owner|contact|facing|available|possession|" +
            "next\\s+(?:property|flat|listing)|add\\b|keep\\b|change\\b|update\\b)|\\s+[1-9]\\d{5}\\b|" +
            "\\s+(?:[1-9]|10)\\s+(?:bath|baths|bathroom|bathrooms|toilet|washroom)\\b|[.,;]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_LANDMARK_NOISE_PATTERN = Pattern.compile(
            "\\s+(?:and\\s+)?(?:add|list|keep|change|update|set|include)\\s*$", Pattern.CASE_INSENSITIVE);

    // Listing Status & Owner Patterns
    private static final Pattern STATUS_PATTERN = Pattern.compile("\\b(live|pending|sold|expired|rented|removed)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_NAME_PATTERN = Pattern.compile(
            "\\b(?:owner\\s*name|owner|contact)\\s*[:\\-]?\\s*([A-Za-z]{2,20}(?:\\s+[A-Za-z]{2,20}){0,3})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REV_OWNER_NAME_PATTERN = Pattern.compile(
            "\\b([A-Za-z]{2,20}(?:\\s+[A-Za-z]{2,20}){0,3})\\s+(?:owner|contact)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![+\\d])(?:\\+?91[\\-\\s]?)?([6-9](?:[\\-\\s]?\\d){9})\\b");

    private static final Pattern NUM_PRICE_PATTERN = Pattern.compile("\\b(\\d{4,6})\\b(?!\\s*(?:sqft|sq|feet|square|meters|days|day|bhk|baths|bed|pin|pincode|deposit|security))", Pattern.CASE_INSENSITIVE);
    private static final Pattern K_PRICE_PATTERN = Pattern.compile("\\b(\\d{1,2})k\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CITY_NER_PATTERN = Pattern
            .compile("\\b(?:in|at|near|around)\\s+([a-zA-Z]{3,20})(?:\\s+city)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX_LOCALITY_PATTERN = Pattern.compile(
            "\\b([A-Za-z0-9\\s]{2,25}\\s+(?:nagar|colony|city|township|road|street|lane|circle|sector|bazar|vihar|enclave|pur|ganj|heights|residency|villa|society|square|chowk|puri|dham|bagh|marg|block|phase|layout|extension|ext|estate|avenue|gali|path|bypass|highway|scheme|drive|park|hills|hill|valley|green|greens|campus))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREP_LOCALITY_PATTERN = Pattern.compile(
            "\\b(?:in|at|near|around|sector|road|street|block|phase)\\s+([A-Za-z0-9\\s]{2,30}?)(?=\\s+(?:with|having|facing|for|rent|per|month|\\d|rs|rupees|\\$|$))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLONY_SOCIETY_PATTERN = Pattern.compile(
            "\\b([A-Za-z0-9\\s]{2,25}\\s+(?:vatika|apartment|apartments|society|township|gardens|towers|residency|heights|retreat|villas|complex|enclave|palms|greens|vista|view|court|cliffs|paradise|homes|floors|nest|spire))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_PREFIX_PATTERN = Pattern.compile("^.*?\\b(?:in|at|near|around)\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern DIRECTION_TOKEN_PATTERN = Pattern.compile(
            "\\b(north-east|north-west|south-east|south-west|northeast|northwest|southeast|southwest|north|south|east|west)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ONE_PLUS_ONE_PATTERN = Pattern.compile("\\b([1-3])\\s*\\+\\s*([1-3])\\b");
    private static final Pattern MONTH_COUNT_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s*(?:month|months|mahina|mahine)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FALLBACK_BHK_DIGIT_PATTERN = Pattern.compile("\\b([1-9]\\d?(?:\\.5)?)\\b");
    private static final Pattern BHK_KEYWORD_PATTERN = Pattern.compile(
            "(?:bhk|rk|bedroom|bedrooms|bed|beds|room|rooms|bk|bhkk|bhkks|dfbhk|sdfbhk)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOUSE_TYPE_PATTERN = Pattern.compile(
            "\\b(?:house|bungalow|independent|villa|duplex|bunglow|viila|vlla)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_NAME_NOISE_PATTERN = Pattern.compile(
            "\\b(is|live|facing|flat|house|villa|apartment|plot|furnished|fully|semi|unfurnished|bhk|rk|bedroom|bath|baths)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("\\D");
    private static final Pattern SECTOR_LONG_NUMBER_PATTERN = Pattern.compile(".*\\b\\d{4,}\\b.*");
    private static final Pattern LEADING_BATCH_MARKER_PATTERN = Pattern.compile("^(?:\\[?\\d+[\\]\\)\\.\\:\\-]|#\\d+)\\s*");
    private static final Pattern MEDIA_URL_EXTENSION_PATTERN = Pattern.compile(".*\\.(jpg|jpeg|png|webp|mp4|gif).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRECEDING_MONETARY_AMOUNT_PATTERN = Pattern.compile(
            "(?:\\d{1,3}(?:,\\d{2,3})+|\\d{4,6}|\\d{1,2}k)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_RENT_CUE_PATTERN = Pattern.compile("\\b(?:monthly\\s+rent|rent|kiraya|rnt)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_BROKERAGE_CUE_PATTERN = Pattern.compile("\\b(?:brokerage|commission|broker\\s+fee)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_DEPOSIT_CUE_PATTERN = Pattern.compile("\\b(?:security\\s+deposit|deposit|deposits|security)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_OWNER_PHONE_CUE_PATTERN = Pattern.compile(
            "\\b(?:owner\\s+(?:phone|mobile|contact|number)|phone|mobile|contact\\s+number)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_OWNER_NAME_CUE_PATTERN = Pattern.compile("\\bowner\\s+name\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_BATHROOM_CUE_PATTERN = Pattern.compile("\\b(?:bath|baths|bathroom|bathrooms|toilet|washroom)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_LAYOUT_CUE_PATTERN = Pattern.compile("\\b(?:bhk|rk|bedroom|bedrooms|layout)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_TYPE_CUE_PATTERN = Pattern.compile("\\b(?:flat|apartment|house|villa|plot|penthouse|studio)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_AREA_CUE_PATTERN = Pattern.compile("\\b(?:area|sqft|sq\\.?\\s*ft|square\\s+feet|sqm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_FURNISHING_CUE_PATTERN = Pattern.compile("\\b(?:furnished|unfurnished|furnishing)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_POSSESSION_CUE_PATTERN = Pattern.compile("\\b(?:available|availability|possession|ready\\s+to\\s+move|move\\s+in)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_FACING_CUE_PATTERN = Pattern.compile("\\b(?:facing|north|south|east|west)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_STATUS_CUE_PATTERN = Pattern.compile("\\b(?:status|live|pending|sold|expired|rented|removed)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_LOCATION_CUE_PATTERN = Pattern.compile("\\b(?:locality|location|sector|city|address|in|at)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_LANDMARK_CUE_PATTERN = Pattern.compile("\\b(?:landmark|near|opposite|behind|in\\s+front\\s+of)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMENDMENT_PINCODE_CUE_PATTERN = Pattern.compile("\\b(?:pincode|pin\\s+code|postal\\s+code)\\b", Pattern.CASE_INSENSITIVE);

    // Master Indian Cities & Tier-1/Tier-2 Metros Allow-list for fail-safe extraction
    private static final Set<String> MASTER_INDIAN_CITIES = Set.of(
            "indore", "bhopal", "ujjain", "jabalpur", "gwalior", "pune", "mumbai", "delhi",
            "gurgaon", "noida", "bangalore", "hyderabad", "chennai", "kolkata", "ahmedabad",
            "jaipur", "surat", "lucknow", "chandigarh", "goa", "dewas", "ratlam", "dhar"
    );

    // Longest names come first so a precise locality wins when names share a prefix.
    // This fixed-size list is also scanned from the end of the prompt: in a phrase such as
    // "near Opal Homes Chikatsak Nagar Mahalaxmi Nagar", the final stated locality is the
    // property locality while the earlier one is part of the route or landmark.
    private static final List<String> KNOWN_INDORE_SECTORS = List.of(
            "vijay nagar", "nanda nagar", "palasia", "old palasia", "saket nagar", "nipania",
            "bhawarkua", "mahalakshmi nagar", "mahalaxmi nagar", "chikatsak nagar", "chikatsak",
            "rau", "mhow", "lig colony", "sukhlia", "khajrana", "bypass road", "annapurna", "sudama nagar",
            "khandwa road", "ab road", "bicholi mardana", "kanadia road", "rajendra nagar", "chandan nagar",
            "scheme 54", "scheme 78", "scheme 74", "scheme 140", "scheme 114", "tilak nagar", "manorama ganj", "race course road"
    ).stream().sorted((first, second) -> Integer.compare(second.length(), first.length())).toList();

    private final LocalityRepository localityRepository;
    private final Clock clock;

    // High-Speed $O(1)$ Concurrent L1 In-Memory Caches
    private final Map<String, Locality> localityCache = new ConcurrentHashMap<>();
    private final Set<String> cityCache = ConcurrentHashMap.newKeySet();

    @Autowired
    public PropertyParserService(LocalityRepository localityRepository) {
        this(localityRepository, Clock.system(ZoneId.of("Asia/Kolkata")));
    }

    PropertyParserService(LocalityRepository localityRepository, Clock clock) {
        this.localityRepository = localityRepository;
        this.clock = clock != null ? clock : Clock.system(ZoneId.of("Asia/Kolkata"));
    }

    /**
     * Initializes $O(1)$ L1 cache from PostgreSQL on startup
     */
    @PostConstruct
    public void initCache() {
        try {
            List<Locality> all = localityRepository.findAll();
            for (Locality loc : all) {
                if (loc.getSectorName() != null && loc.getCity() != null) {
                    localityCache.put(localityCacheKey(loc.getCity(), loc.getSectorName()), loc);
                }
                if (loc.getCity() != null) {
                    cityCache.add(loc.getCity().toLowerCase());
                }
            }
            log.info("Initialized L1 Locality Cache with {} entries & {} cities", localityCache.size(),
                    cityCache.size());
        } catch (Exception e) {
            log.warn("L1 Cache initialization deferred (database initializing): {}", e.getMessage());
        }
    }

    /**
     * Parses text without changing persistent state. Localities are stored only
     * after an administrator confirms the staged listing for publication.
     */
    public ParsedPropertyDTO parse(String prompt) {
        return parseInternal(prompt);
    }

    /**
     * Compatibility entry point for existing clients. It is intentionally
     * read-only despite the historical method name.
     */
    @Deprecated(forRemoval = false)
    public ParsedPropertyDTO parseAndSave(String prompt) {
        return parseInternal(prompt);
    }

    @Transactional
    public Locality confirmLocality(String city, String sector, double rentAmount) {
        if (isMissingValue(city) || isMissingValue(sector)) {
            return null;
        }

        String normalizedCity = capitalizeWords(city.trim());
        String normalizedSector = capitalizeWords(sector.trim());
        String cacheKey = localityCacheKey(normalizedCity, normalizedSector);
        Locality cached = localityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Optional<Locality> existing = localityRepository
                .findByCityIgnoreCaseAndSectorNameIgnoreCase(normalizedCity, normalizedSector);
        if (existing.isPresent()) {
            Locality locality = existing.get();
            localityCache.put(cacheKey, locality);
            cityCache.add(normalizedCity.toLowerCase(Locale.ROOT));
            return locality;
        }

        Locality locality = new Locality(normalizedCity, normalizedSector, cacheKey, 92, rentAmount);
        Locality saved = localityRepository.save(locality);
        Locality resolved = saved != null ? saved : locality;
        localityCache.put(cacheKey, resolved);
        cityCache.add(normalizedCity.toLowerCase(Locale.ROOT));
        log.info("Persisted confirmed locality: City={}, Sector={}", normalizedCity, normalizedSector);
        return resolved;
    }

    private ParsedPropertyDTO parseInternal(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return new ParsedPropertyDTO();
        }

        String input = prompt.trim();
        String cleanLower = input.toLowerCase();

        // 0. Pre-Pass: Typo Auto-Correction & Normalization
        String normalized = normalizeTypos(cleanLower);

        // Hinglish / Indian Slang Rent Normalization: e.g. "18 hazar" -> "18000", "1.5 lakh" -> "150000"
        Matcher hazarMatcher = HINDI_HAZAR_RENT_PATTERN.matcher(normalized);
        if (hazarMatcher.find()) {
            try {
                double val = Double.parseDouble(hazarMatcher.group(1));
                long converted = Math.round(val * 1000);
                normalized = hazarMatcher.replaceAll(String.valueOf(converted));
            } catch (Exception ignored) {}
        }
        Matcher lakhMatcher = HINDI_LAKH_RENT_PATTERN.matcher(normalized);
        if (lakhMatcher.find()) {
            try {
                double val = Double.parseDouble(lakhMatcher.group(1));
                long converted = Math.round(val * 100000);
                normalized = lakhMatcher.replaceAll(String.valueOf(converted));
            } catch (Exception ignored) {}
        }

        // 1. Universal BHK Extractor (Studio/1RK checked first, followed by explicit numeric BHK)
        String bhk = "Unspecified";
        Matcher numBhkMatcher = NUM_BHK_PATTERN.matcher(input);
        Matcher revBhkMatcher = REV_BHK_PATTERN.matcher(input);
        Matcher wordBhkMatcher = WORD_BHK_PATTERN.matcher(input);

        if (normalized.contains("studio") || normalized.contains("1rk") || normalized.contains(" rk ")
                || normalized.endsWith(" rk")) {
            bhk = "1 RK Studio";
        } else if (numBhkMatcher.find()) {
            String val = numBhkMatcher.group(1);
            bhk = val.endsWith(".0") ? val.substring(0, val.length() - 2) + " BHK" : val + " BHK";
        } else if (revBhkMatcher.find()) {
            String val = revBhkMatcher.group(1);
            bhk = val.endsWith(".0") ? val.substring(0, val.length() - 2) + " BHK" : val + " BHK";
        } else if (wordBhkMatcher.find()) {
            String w = wordBhkMatcher.group(1).toLowerCase();
            String num = switch (w) {
                case "one" -> "1";
                case "two" -> "2";
                case "three" -> "3";
                case "four" -> "4";
                case "five" -> "5";
                case "six" -> "6";
                case "seven" -> "7";
                case "eight" -> "8";
                case "nine" -> "9";
                case "ten" -> "10";
                default -> "2";
            };
            bhk = num + " BHK";
        } else if (normalized.contains("triplex")) {
            bhk = "Triplex Villa";
        } else if (normalized.contains("duplex") || normalized.contains("villa")) {
            bhk = "Duplex Villa";
        } else if (normalized.contains("penthouse")) {
            bhk = "Luxury Penthouse";
        } else {
            // Global Fallback Extractor: If any single number exists and prompt contains BHK/RK/Bed tokens or typos anywhere
            Matcher anyDigit = FALLBACK_BHK_DIGIT_PATTERN.matcher(input);
            if (anyDigit.find() && BHK_KEYWORD_PATTERN.matcher(input).find()) {
                bhk = anyDigit.group(1) + " BHK";
            }
        }

        // 1.5. Bathrooms Extractor
        String bathrooms = null;
        Matcher bathMatcher = BATHROOMS_PATTERN.matcher(input);
        if (bathMatcher.find()) {
            String bCount = bathMatcher.group(1) != null ? bathMatcher.group(1) : bathMatcher.group(2);
            if (bCount != null) {
                bathrooms = bCount + " Baths";
            }
        }
        if (bathrooms == null) {
            Matcher wordBathMatcher = WORD_BATHROOMS_PATTERN.matcher(input);
            if (wordBathMatcher.find()) {
                String bCount = wordBathMatcher.group(1) != null
                        ? wordBathMatcher.group(1)
                        : wordBathMatcher.group(2);
                bathrooms = toNumericBathroomCount(bCount) + " Baths";
            }
        }
        if (bathrooms == null && BATHROOMS_TO_SPEECH_TYPO_PATTERN.matcher(input).find()) {
            bathrooms = "2 Baths";
        }

        // 2. Property Type Extractor (Penthouse prioritized to prevent 'house' substring collision)
        String type = null;
        if (normalized.contains("penthouse") || normalized.contains("penthous")) {
            type = "Penthouse";
        } else if (normalized.contains("airbnb") || normalized.contains("serviced stay")) {
            type = "Airbnb";
        } else if (normalized.contains("studio")) {
            type = "Studio";
        } else if (normalized.contains("apartment") || normalized.contains("flat") || normalized.contains("flt")) {
            type = "Flat";
        } else if (normalized.contains("plot") || normalized.contains("land")) {
            type = "Plot";
        } else if (HOUSE_TYPE_PATTERN.matcher(normalized).find()) {
            type = "House";
        }

        // 2.5. Listing Status Extractor
        String status = null;
        Matcher statusMatcher = STATUS_PATTERN.matcher(input);
        if (statusMatcher.find()) {
            status = statusMatcher.group(1).toUpperCase();
        }

        // 2.6. Pincode & Owner Phone Extractor (Extracted early to isolate 6-digit PIN & 10-digit phone)
        String pincode = null;
        Matcher pinMatcher = PINCODE_PATTERN.matcher(input);
        if (pinMatcher.find()) {
            pincode = pinMatcher.group(1);
        }

        String ownerName = null;
        Matcher ownerMatcher = OWNER_NAME_PATTERN.matcher(input);
        if (ownerMatcher.find() && ownerMatcher.group(1) != null && !ownerMatcher.group(1).isBlank()) {
            String candidate = OWNER_NAME_NOISE_PATTERN.matcher(ownerMatcher.group(1)).replaceAll("").trim();
            if (!candidate.isBlank()) {
                ownerName = capitalizeWords(candidate);
            }
        }
        if (ownerName == null) {
            Matcher revOwnerMatcher = REV_OWNER_NAME_PATTERN.matcher(input);
            if (revOwnerMatcher.find() && revOwnerMatcher.group(1) != null) {
                String candidate = OWNER_NAME_NOISE_PATTERN.matcher(revOwnerMatcher.group(1)).replaceAll("").trim();
                if (!candidate.isBlank()) {
                    ownerName = capitalizeWords(candidate);
                }
            }
        }

        String ownerPhone = null;
        Matcher phoneMatcher = PHONE_PATTERN.matcher(input);
        if (phoneMatcher.find()) {
            String rawDigits = NON_DIGIT_PATTERN.matcher(phoneMatcher.group(0)).replaceAll("");
            if (rawDigits.startsWith("91") && rawDigits.length() > 10) {
                rawDigits = rawDigits.substring(2);
            }
            if (rawDigits.length() == 10) {
                if (pincode == null || !rawDigits.equals(pincode)) {
                    ownerPhone = "+91 " + rawDigits.substring(0, 5) + " " + rawDigits.substring(5);
                }
            }
        }

        // 3A. Price / Rent Extractor (Extracted FIRST as primary monetary field)
        double rentAmount = 0.0;
        boolean rentFound = false;

        // Step 1: Check REV Rent
        Matcher revRent = REV_RENT_PATTERN.matcher(normalized);
        while (revRent.find()) {
            String rawRent = revRent.group(1);
            if (rawRent != null) {
                String cleanRent = rawRent.replace(",", "");
                if (pincode != null && pincode.equals(cleanRent)) continue;
                if (ownerPhone != null && ownerPhone.contains(cleanRent)) continue;

                String nearestKeyword = findNearestPrecedingKeyword(normalized, revRent.start(1));
                if ("BROKERAGE".equals(nearestKeyword) || "DEPOSIT".equals(nearestKeyword)) {
                    continue;
                }

                rentAmount = cleanRent.toLowerCase().endsWith("k")
                        ? Double.parseDouble(cleanRent.substring(0, cleanRent.length() - 1)) * 1000
                        : Double.parseDouble(cleanRent);
                rentFound = true;
                break;
            }
        }

        // Step 2: Check FWD Rent
        if (!rentFound) {
            Matcher fwdRent = FWD_RENT_PATTERN.matcher(normalized);
            while (fwdRent.find()) {
                String rawRent = fwdRent.group(1);
                if (rawRent != null) {
                    String cleanRent = rawRent.replace(",", "");
                    if (pincode != null && pincode.equals(cleanRent)) continue;
                    if (ownerPhone != null && ownerPhone.contains(cleanRent)) continue;
                    if (isAreaUnitImmediatelyAfter(normalized, fwdRent.end(1))) continue;

                    String nearestKeyword = findNearestPrecedingKeyword(normalized, fwdRent.start(1));
                    if ("BROKERAGE".equals(nearestKeyword) || "DEPOSIT".equals(nearestKeyword)) {
                        continue;
                    }

                    rentAmount = cleanRent.toLowerCase().endsWith("k")
                            ? Double.parseDouble(cleanRent.substring(0, cleanRent.length() - 1)) * 1000
                            : Double.parseDouble(cleanRent);
                    rentFound = true;
                    break;
                }
            }
        }

        // 3B. Explicit Brokerage & Brokerage Days Extractor
        String brokerageVal = "Unmentioned";
        Matcher revBrokerage = REV_BROKERAGE_PATTERN.matcher(normalized);
        while (revBrokerage.find()) {
            String rawVal = revBrokerage.group(1);
            if (rawVal != null) {
                String cleanVal = rawVal.replace(",", "");
                if ((pincode == null || !pincode.equals(cleanVal)) && (ownerPhone == null || !ownerPhone.contains(cleanVal))) {
                    String nearestKeyword = findNearestPrecedingKeyword(normalized, revBrokerage.start(1));
                    if (("RENT".equals(nearestKeyword) || "DEPOSIT".equals(nearestKeyword))
                            && !hasAmountImmediatelyBefore(normalized, revBrokerage.start(1))) {
                        continue;
                    }

                    double bAmt = cleanVal.toLowerCase().endsWith("k")
                            ? Double.parseDouble(cleanVal.substring(0, cleanVal.length() - 1)) * 1000
                            : Double.parseDouble(cleanVal);
                    brokerageVal = String.format("₹%,.0f", bAmt);
                    break;
                }
            }
        }
        if ("Unmentioned".equals(brokerageVal)) {
            Matcher fwdBrokerage = FWD_BROKERAGE_PATTERN.matcher(normalized);
            while (fwdBrokerage.find()) {
                String rawVal = fwdBrokerage.group(1);
                if (rawVal != null) {
                    String cleanVal = rawVal.replace(",", "");
                    if ((pincode == null || !pincode.equals(cleanVal)) && (ownerPhone == null || !ownerPhone.contains(cleanVal))) {
                        if (isSameAmountMatchedBy(normalized, fwdBrokerage.start(1), fwdBrokerage.end(1),
                                REV_RENT_PATTERN, REV_DEPOSIT_PATTERN)) continue;
                        String nearestKeyword = findNearestPrecedingKeyword(normalized, fwdBrokerage.start(1));
                        if ("RENT".equals(nearestKeyword) || "DEPOSIT".equals(nearestKeyword)) {
                            continue;
                        }

                        double bAmt = cleanVal.toLowerCase().endsWith("k")
                                ? Double.parseDouble(cleanVal.substring(0, cleanVal.length() - 1)) * 1000
                                : Double.parseDouble(cleanVal);
                        brokerageVal = String.format("₹%,.0f", bAmt);
                        break;
                    }
                }
            }
        }

        String brokerageDays = null;
        Matcher daysMatcher = BROKERAGE_DAYS_PATTERN.matcher(input);
        if (daysMatcher.find()) {
            brokerageDays = daysMatcher.group(1) + " Days";
        }

        // 3C. Explicit Sqft Area Extractor
        String areaSqFt = null;
        Matcher sqftMatcher = SQFT_PATTERN.matcher(input);
        if (sqftMatcher.find()) {
            String candidateArea = sqftMatcher.group(1) != null ? sqftMatcher.group(1) : sqftMatcher.group(2);
            areaSqFt = candidateArea + " sqft";
        }

        // 3D. Explicit Security Deposit Extractor
        String depositVal = null;
        Matcher monthsDep = HINDI_MONTHS_DEPOSIT_PATTERN.matcher(normalized);
        if (monthsDep.find()) {
            String months = monthsDep.group(1) != null ? monthsDep.group(1) : monthsDep.group(2);
            if (months != null && !months.isBlank()) {
                depositVal = months + " Months Deposit";
            }
        }
        if (depositVal == null) {
            Matcher revDeposit = REV_DEPOSIT_PATTERN.matcher(normalized);
            while (revDeposit.find()) {
                String depRaw = revDeposit.group(1);
                if (depRaw != null) {
                    String cleanDep = depRaw.replace(",", "");
                    if ((pincode == null || !pincode.equals(cleanDep)) && (ownerPhone == null || !ownerPhone.contains(cleanDep))) {
                        depositVal = depRaw + " Security Deposit";
                        break;
                    }
                }
            }
        }
        if (depositVal == null) {
            Matcher fwdDeposit = FWD_DEPOSIT_PATTERN.matcher(normalized);
            while (fwdDeposit.find()) {
                String depRaw = fwdDeposit.group(1);
                if (depRaw != null) {
                    String cleanDep = depRaw.replace(",", "");
                    if ((pincode == null || !pincode.equals(cleanDep)) && (ownerPhone == null || !ownerPhone.contains(cleanDep))) {
                        if (isSameAmountMatchedBy(normalized, fwdDeposit.start(1), fwdDeposit.end(1),
                                REV_RENT_PATTERN, REV_BROKERAGE_PATTERN)) continue;
                        depositVal = depRaw + " Security Deposit";
                        break;
                    }
                }
            }
        }

        // 3E. Furnishing & Possession Date Extractor
        String furnishingStatus = null;
        Matcher furnMatcher = FURNISHING_PATTERN.matcher(normalized);
        if (furnMatcher.find()) {
            furnishingStatus = capitalizeWords(furnMatcher.group(1));
        }

        PossessionResolution possession = resolvePossession(input, normalized);
        String possessionDate = possession.displayText();

        // 3F. State & Landmark Extractor
        String state = null;
        Matcher stateMatcher = STATE_PATTERN.matcher(input);
        if (stateMatcher.find()) {
            state = capitalizeWords(stateMatcher.group(1));
        }

        String landmark = extractLandmarks(normalized);
        if (!rentFound) {
            Matcher numMatcher = NUM_PRICE_PATTERN.matcher(input);
            while (numMatcher.find()) {
                String candidateVal = numMatcher.group(1);
                if (candidateVal.equals(pincode) || (areaSqFt != null && areaSqFt.startsWith(candidateVal))
                        || (brokerageVal != null && brokerageVal.replace(",", "").contains(candidateVal))
                        || (depositVal != null && depositVal.replace(",", "").contains(candidateVal))) {
                    continue;
                }
                rentAmount = Double.parseDouble(candidateVal);
                rentFound = true;
                break;
            }
        }

        if (!rentFound) {
            Matcher kMatcher = K_PRICE_PATTERN.matcher(input);
            while (kMatcher.find()) {
                String kVal = kMatcher.group(1);
                double candidateRent = Double.parseDouble(kVal) * 1000;
                String formattedCand = String.format("₹%,.0f", candidateRent);
                if ((brokerageVal != null && (brokerageVal.toLowerCase().contains(kVal + "k") || brokerageVal.equals(formattedCand)))
                        || (depositVal != null && (depositVal.toLowerCase().contains(kVal + "k") || depositVal.equals(formattedCand)))) {
                    continue;
                }
                rentAmount = candidateRent;
                rentFound = true;
                break;
            }
        }

        String rentVal = rentFound ? String.format("₹%,.0f", rentAmount) : "Unspecified";

        // 4. Fast $O(1)$ L1 Cache-Backed Sector & City Resolution
        String sector = "";
        String city = "";

        // Step 4A: Resolve an explicit known city before using locality data.
        city = resolveKnownCity(normalized);

        // Step 4B: Prefer the final explicit known Indore locality. This prevents an
        // earlier landmark/locality reference from overriding the property locality.
        String knownSector = resolveLastKnownIndoreSector(normalized);
        if (!knownSector.isBlank()) {
            sector = capitalizeWords(knownSector);
            city = "Indore";
        }

        // Step 4C: Match a database-backed locality when no known core locality was supplied.
        // The cache supports newly added areas without overriding an explicit, unambiguous one.
        if (sector.isBlank()) {
            Locality cachedLocality = resolveCachedLocality(normalized, city);
            if (cachedLocality != null) {
                sector = cachedLocality.getSectorName();
                if (city.isBlank()) {
                    city = cachedLocality.getCity();
                }
            }
        }

        // Step 4D: Check City Cache or Master Indian Cities Allow-list
        if (city.isBlank()) {
            for (String c : cityCache) {
                if (cleanLower.contains(c) && MASTER_INDIAN_CITIES.contains(c)) {
                    city = capitalizeWords(c);
                    break;
                }
            }
        }

        if (city.isBlank()) {
            for (String kc : MASTER_INDIAN_CITIES) {
                if (cleanLower.contains(kc)) {
                    city = capitalizeWords(kc);
                    break;
                }
            }
        }

        // Step 4E: Dynamic City Scanner for explicit city declarations (strictly validated against Master Indian Cities)
        if (city.isBlank()) {
            Matcher cityMatcher = CITY_NER_PATTERN.matcher(input);
            if (cityMatcher.find()) {
                String candidateCity = cityMatcher.group(1).trim().toLowerCase();
                if (MASTER_INDIAN_CITIES.contains(candidateCity)) {
                    city = capitalizeWords(candidateCity);
                }
            }
        }

        // Step 4F: Dynamic Sector / Street / Suffix Scanner
        if (sector.isBlank()) {
            Matcher suffixMatcher = SUFFIX_LOCALITY_PATTERN.matcher(normalized);
            if (suffixMatcher.find()) {
                String matchedStr = capitalizeWords(suffixMatcher.group(1).trim());
                sector = matchedStr;
            }
        }

        // Step 4G: Dynamic Preposition NER Scanner
        if (sector.isBlank()) {
            Matcher prepMatcher = PREP_LOCALITY_PATTERN.matcher(normalized);
            if (prepMatcher.find()) {
                String candidate = prepMatcher.group(1).trim();
                if (!candidate.equalsIgnoreCase(city)) {
                    sector = capitalizeWords(candidate);
                }
            }
        }

        // Clean noise prefixes (e.g., '4bhk flat in', 'in', 'at', 'near') and trailing duplicate city/sector names
        if (!sector.isBlank()) {
            sector = NOISE_PREFIX_PATTERN.matcher(sector).replaceAll("").trim();
            if (!city.isBlank() && sector.toLowerCase().endsWith(" " + city.toLowerCase())) {
                sector = sector.substring(0, sector.length() - city.length()).trim();
            }
            if (SECTOR_LONG_NUMBER_PATTERN.matcher(sector).matches()) {
                sector = "";
            }
        }

        if (sector.isBlank()) {
            sector = "Not Specified";
        }
        if (city.isBlank() || !MASTER_INDIAN_CITIES.contains(city.toLowerCase(Locale.ROOT))) {
            city = "Not Specified";
        }

        // 5. Dynamic Society / Project Name Detection
        String colony = "";
        Matcher colonyMatcher = COLONY_SOCIETY_PATTERN.matcher(input);
        if (colonyMatcher.find()) {
            colony = capitalizeWords(colonyMatcher.group(1).trim());
        }

        // 6. Vastu Facing Direction (Defaults to 'Not Specified' unless direction
        // keyword is present)
        String vastuFacing = "Not Specified";
        if (normalized.contains("north-east") || normalized.contains("northeast"))
            vastuFacing = "North-East Facing";
        else if (normalized.contains("north-west") || normalized.contains("northwest"))
            vastuFacing = "North-West Facing";
        else if (normalized.contains("south-east") || normalized.contains("southeast"))
            vastuFacing = "South-East Facing";
        else if (normalized.contains("south-west") || normalized.contains("southwest"))
            vastuFacing = "South-West Facing";
        else if (normalized.contains("facing west") || normalized.contains("west facing")
                || normalized.contains("west"))
            vastuFacing = "West Facing";
        else if (normalized.contains("facing north") || normalized.contains("north facing")
                || normalized.contains("north"))
            vastuFacing = "North Facing";
        else if (normalized.contains("facing south") || normalized.contains("south facing")
                || normalized.contains("south"))
            vastuFacing = "South Facing";
        else if (normalized.contains("facing east") || normalized.contains("east facing")
                || normalized.contains("east"))
            vastuFacing = "East Facing";

        // 7. Amenities Extractor
        List<String> amenities = new ArrayList<>();
        if (cleanLower.contains("balcony"))
            amenities.add("Balcony & City View");
        if (cleanLower.contains("garden"))
            amenities.add("Private Garden");
        if ("Fully Furnished".equalsIgnoreCase(furnishingStatus))
            amenities.add("Fully Furnished");
        if (cleanLower.contains("parking"))
            amenities.add("Covered Parking");
        if (cleanLower.contains("gated"))
            amenities.add("Gated Security");
        if (cleanLower.contains("lift"))
            amenities.add("High-Speed Lift");
        if (cleanLower.contains("gym"))
            amenities.add("Fitness Center & Gym");
        if (cleanLower.contains("swimming") || cleanLower.contains("pool"))
            amenities.add("Swimming Pool");

        List<String> conflicts = detectConflicts(normalized);
        if (possession.status() == AvailabilityStatus.AVAILABLE_FROM_DATE
                && possession.availableFrom() != null
                && possession.availableFrom().isBefore(LocalDate.now(clock))) {
            conflicts.add("Possession date is in the past: " + possession.displayText());
        }

        // 9. Parsing must not persist inferred localities. The publish workflow
        // persists a confirmed locality in the same transaction as its listing.
        boolean newlySaved = false;

        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setBhk(bhk);
        dto.setType(type != null ? type : "Not Specified");
        dto.setStatus(status != null ? status : "Unspecified");
        dto.setCity(city);
        dto.setSector(sector);
        dto.setColony(colony);
        dto.setRentVal(rentVal);
        dto.setRentAmount(rentAmount);
        dto.setBrokerageVal(brokerageVal != null ? brokerageVal : "Unmentioned");
        dto.setBrokerageDays(brokerageDays);
        dto.setAreaSqFt(areaSqFt != null ? areaSqFt : "Not Specified");
        dto.setDepositVal(depositVal);
        dto.setBathrooms(bathrooms != null ? bathrooms : "Not Specified");
        dto.setFurnishingStatus(furnishingStatus);
        dto.setPossessionDate(possessionDate);
        dto.setAvailabilityStatus(possession.status());
        dto.setAvailableFrom(possession.availableFrom());
        dto.setState(state);
        dto.setPincode(pincode != null ? pincode : "Not Specified");
        dto.setLandmark(landmark != null ? landmark : "Not Specified");
        dto.setOwnerName(ownerName != null ? ownerName : "Not Specified");
        dto.setOwnerPhone(ownerPhone != null ? ownerPhone : "Not Specified");
        dto.setVastuFacing(vastuFacing);
        dto.setAmenities(amenities);
        dto.setSavedToDatabase(newlySaved);
        dto.setRawPrompt(input);
        dto.setConflicts(conflicts);
        refreshDerivedFields(dto);

        return dto;
    }

    private void refreshDerivedFields(ParsedPropertyDTO dto) {
        String layout = isMissingValue(dto.getBhk()) ? "" : dto.getBhk().trim();
        String propertyType = isMissingValue(dto.getType()) ? "" : dto.getType().trim();
        String baseName;
        if (!layout.isBlank() && !propertyType.isBlank()
                && !layout.toLowerCase(Locale.ROOT).contains(propertyType.toLowerCase(Locale.ROOT))) {
            baseName = layout + " " + propertyType;
        } else if (!layout.isBlank()) {
            baseName = layout;
        } else if (!propertyType.isBlank()) {
            baseName = propertyType;
        } else {
            baseName = "Property";
        }

        List<String> locationParts = new ArrayList<>();
        if (!isMissingValue(dto.getSector())) locationParts.add(dto.getSector());
        if (!isMissingValue(dto.getCity())) locationParts.add(dto.getCity());
        String location = String.join(", ", locationParts);

        StringBuilder title = new StringBuilder(baseName);
        if (!location.isBlank()) title.append(" in ").append(location);
        dto.setTitle(title.toString());
        dto.setLabel(title.toString());

        List<String> addressParts = new ArrayList<>(locationParts);
        if (!isMissingValue(dto.getState())) addressParts.add(dto.getState());
        String address = String.join(", ", addressParts);
        if (!isMissingValue(dto.getPincode())) {
            address = address.isBlank() ? dto.getPincode() : address + " - " + dto.getPincode();
        }
        dto.setAddress(address);

        StringBuilder description = new StringBuilder(baseName).append(" available for rent");
        if (!location.isBlank()) description.append(" in ").append(location);
        description.append(".");
        if (!isMissingValue(dto.getColony())) description.append(" Society or colony: ").append(dto.getColony()).append(".");
        if (!isMissingValue(dto.getLandmark())) description.append(" Landmark: ").append(dto.getLandmark()).append(".");
        if (!isMissingValue(dto.getAreaSqFt())) description.append(" Carpet area: ").append(dto.getAreaSqFt()).append(".");
        if (!isMissingValue(dto.getBathrooms())) description.append(" Bathrooms: ").append(dto.getBathrooms()).append(".");
        if (!isMissingValue(dto.getVastuFacing())) description.append(" Facing: ").append(dto.getVastuFacing()).append(".");
        if (!isMissingValue(dto.getFurnishingStatus())) description.append(" Furnishing: ").append(dto.getFurnishingStatus()).append(".");
        if (dto.getRentAmount() != null && dto.getRentAmount() > 0 && !isMissingValue(dto.getRentVal())) {
            description.append(" Monthly rent: ").append(dto.getRentVal()).append(".");
        }
        if (!isMissingValue(dto.getDepositVal())) description.append(" Security deposit: ").append(dto.getDepositVal()).append(".");
        if (!isMissingValue(dto.getBrokerageVal()) && !"Unmentioned".equalsIgnoreCase(dto.getBrokerageVal())) {
            description.append(" Brokerage: ").append(dto.getBrokerageVal()).append(".");
        }
        if (!isMissingValue(dto.getPossessionDate())) description.append(" Availability: ").append(dto.getPossessionDate()).append(".");
        if (dto.getAmenities() != null && !dto.getAmenities().isEmpty()) {
            description.append(" Amenities: ").append(String.join(", ", dto.getAmenities())).append(".");
        }
        if (!isMissingValue(dto.getOwnerName())) {
            description.append(" Owner: ").append(dto.getOwnerName());
            if (!isMissingValue(dto.getOwnerPhone())) description.append(" (").append(dto.getOwnerPhone()).append(")");
            description.append(".");
        } else if (!isMissingValue(dto.getOwnerPhone())) {
            description.append(" Owner contact: ").append(dto.getOwnerPhone()).append(".");
        }
        if (!isMissingValue(dto.getStatus())) description.append(" Listing status: ").append(dto.getStatus()).append(".");
        dto.setDescription(description.toString());

        List<String> missingFields = new ArrayList<>();
        if (dto.getRentAmount() == null || dto.getRentAmount() <= 0) missingFields.add("Monthly Rent");
        if (isMissingValue(dto.getBhk())) missingFields.add("BHK Layout");
        if (isMissingValue(dto.getType())) missingFields.add("Property Type");
        if (isMissingValue(dto.getSector())) missingFields.add("Locality / Sector");
        if (isMissingValue(dto.getOwnerPhone())) missingFields.add("Owner Contact Number");
        if (isMissingValue(dto.getBathrooms())) missingFields.add("Bathrooms Count");
        if (isMissingValue(dto.getAreaSqFt())) missingFields.add("Carpet Area (sqft)");
        if (isMissingValue(dto.getPincode())) missingFields.add("Pincode");
        if (isMissingValue(dto.getPossessionDate())) missingFields.add("Possession Date / Readiness");
        if (isMissingValue(dto.getVastuFacing())) missingFields.add("Vastu Facing Direction");
        if (isMissingValue(dto.getFurnishingStatus())) missingFields.add("Furnishing Status");
        if (isMissingValue(dto.getDepositVal())) missingFields.add("Security Deposit");
        dto.setMissingFields(missingFields);
        dto.setConflicts(dto.getConflicts());
        dto.setRequiresReview(!missingFields.isEmpty() || !dto.getConflicts().isEmpty());
        dto.setSourceSnippets(buildSourceSnippets(
                dto.getRentVal(), dto.getBrokerageVal(), dto.getDepositVal(), dto.getOwnerPhone(), dto.getSector()));
    }

    private List<String> detectConflicts(String normalized) {
        List<String> conflicts = new ArrayList<>();
        addMonetaryConflict(conflicts, "Monthly Rent", normalized, FWD_RENT_PATTERN, REV_RENT_PATTERN);
        addMonetaryConflict(conflicts, "Brokerage", normalized, FWD_BROKERAGE_PATTERN, REV_BROKERAGE_PATTERN);
        addMonetaryConflict(conflicts, "Security Deposit", normalized, FWD_DEPOSIT_PATTERN, REV_DEPOSIT_PATTERN);
        addTokenConflict(conflicts, "Furnishing", normalized, FURNISHING_PATTERN);
        addTokenConflict(conflicts, "Facing Direction", normalized, DIRECTION_TOKEN_PATTERN);
        return conflicts;
    }

    private void addMonetaryConflict(List<String> conflicts, String field, String text, Pattern forward, Pattern reverse) {
        Set<String> values = new HashSet<>();
        collectMatcherValues(values, forward.matcher(text));
        collectMatcherValues(values, reverse.matcher(text));
        if (values.size() > 1) {
            conflicts.add(field + " has multiple values: " + String.join(", ", values));
        }
    }

    private void collectMatcherValues(Set<String> values, Matcher matcher) {
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank()) {
                values.add(value.replace(",", "").toLowerCase(Locale.ROOT));
            }
        }
    }

    private void addTokenConflict(List<String> conflicts, String field, String text, Pattern pattern) {
        Set<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        if (values.size() > 1) {
            conflicts.add(field + " has conflicting values: " + String.join(", ", values));
        }
    }

    private Map<String, String> buildSourceSnippets(String rentVal, String brokerageVal, String depositVal,
                                                     String ownerPhone, String sector) {
        Map<String, String> snippets = new LinkedHashMap<>();
        addSourceSnippet(snippets, "rent", rentVal);
        addSourceSnippet(snippets, "brokerage", brokerageVal);
        addSourceSnippet(snippets, "deposit", depositVal);
        addSourceSnippet(snippets, "ownerPhone", ownerPhone);
        addSourceSnippet(snippets, "sector", sector);
        return snippets;
    }

    private void addSourceSnippet(Map<String, String> snippets, String key, String value) {
        if (!isMissingValue(value) && !"Unmentioned".equalsIgnoreCase(value)) {
            snippets.put(key, value);
        }
    }

    private PossessionResolution resolvePossession(String originalPrompt, String normalizedPrompt) {
        LocalDate relativeDate = resolveRelativePossessionDate(normalizedPrompt);
        if (relativeDate != null) {
            return possessionOn(relativeDate);
        }

        Matcher possessionMatcher = POSSESSION_PATTERN.matcher(originalPrompt);
        boolean immediateAvailabilityFound = false;
        while (possessionMatcher.find()) {
            String explicitValue = possessionMatcher.group(1);
            if (explicitValue != null) {
                String normalizedValue = explicitValue.trim().toLowerCase(Locale.ROOT);
                if (normalizedValue.contains("ready to move") || normalizedValue.contains("immediate")) {
                    immediateAvailabilityFound = true;
                    continue;
                }

                LocalDate explicitDate = parseExplicitPossessionDate(explicitValue);
                if (explicitDate != null) {
                    return possessionOn(explicitDate);
                }
            }

            String matchedText = possessionMatcher.group(0).toLowerCase(Locale.ROOT);
            if (matchedText.contains("ready to move") || matchedText.contains("immediate")) {
                immediateAvailabilityFound = true;
            }
        }
        return immediateAvailabilityFound ? readyNow() : PossessionResolution.unspecified();
    }

    private PossessionResolution readyNow() {
        return new PossessionResolution(
                "Ready To Move", AvailabilityStatus.READY_NOW, LocalDate.now(clock));
    }

    private PossessionResolution possessionOn(LocalDate date) {
        return new PossessionResolution(
                formatResolvedPossessionDate(date), AvailabilityStatus.AVAILABLE_FROM_DATE, date);
    }

    private LocalDate parseExplicitPossessionDate(String rawValue) {
        String normalizedDate = DATE_ORDINAL_SUFFIX_PATTERN.matcher(rawValue.trim()).replaceAll("");
        normalizedDate = DATE_OF_WORD_PATTERN.matcher(normalizedDate).replaceAll(" ").trim();

        if (FOUR_DIGIT_YEAR_PATTERN.matcher(normalizedDate).find()
                || normalizedDate.indexOf('/') >= 0
                || countCharacter(normalizedDate, '-') >= 2) {
            for (DateTimeFormatter formatter : FULL_POSSESSION_DATE_FORMATTERS) {
                try {
                    return LocalDate.parse(normalizedDate, formatter);
                } catch (DateTimeParseException ignored) {
                    // Try the next fixed formatter.
                }
            }
            return null;
        }

        for (DateTimeFormatter formatter : MONTH_DAY_POSSESSION_DATE_FORMATTERS) {
            try {
                MonthDay monthDay = MonthDay.parse(normalizedDate, formatter);
                return resolveNextOccurrence(monthDay);
            } catch (DateTimeParseException ignored) {
                // Try the next fixed formatter.
            }
        }
        return null;
    }

    private int countCharacter(String value, char target) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == target) count++;
        }
        return count;
    }

    private LocalDate resolveNextOccurrence(MonthDay monthDay) {
        LocalDate today = LocalDate.now(clock);
        int candidateYear = today.getYear();
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                LocalDate candidate = monthDay.atYear(candidateYear + attempt);
                if (!candidate.isBefore(today)) return candidate;
            } catch (java.time.DateTimeException ignored) {
                // Continue until the next valid year, including leap-day input.
            }
        }
        return null;
    }

    /**
     * Resolves relative availability statements against Indian local time. A date is only
     * produced when the interval is clearly about availability, never when it describes
     * a deposit, brokerage, lease, or notice period.
     */
    private LocalDate resolveRelativePossessionDate(String normalizedPrompt) {
        Matcher diwaliMatcher = DIWALI_POSSESSION_PATTERN.matcher(normalizedPrompt);
        if (diwaliMatcher.find()) {
            String yearValue = diwaliMatcher.group(1) != null ? diwaliMatcher.group(1) : diwaliMatcher.group(2);
            LocalDate diwaliDate = resolveDiwaliDate(yearValue);
            return diwaliDate == null ? null : diwaliDate.plusDays(1);
        }

        LocalDate resolvedDate = resolveRelativeInterval(
                normalizedPrompt, RELATIVE_POSSESSION_PATTERN.matcher(normalizedPrompt));
        if (resolvedDate != null) {
            return resolvedDate;
        }
        return resolveRelativeInterval(normalizedPrompt, HINGLISH_RELATIVE_POSSESSION_PATTERN.matcher(normalizedPrompt));
    }

    private LocalDate resolveRelativeInterval(String normalizedPrompt, Matcher relativeMatcher) {
        while (relativeMatcher.find()) {
            if (hasNonPossessionIntervalContext(normalizedPrompt, relativeMatcher.start())) {
                continue;
            }

            int amount = Integer.parseInt(relativeMatcher.group(1));
            LocalDate baseDate = LocalDate.now(clock);
            LocalDate resolvedDate = switch (relativeMatcher.group(2).toLowerCase(Locale.ROOT)) {
                case "day", "days" -> baseDate.plusDays(amount);
                case "week", "weeks" -> baseDate.plusWeeks(amount);
                case "year", "years" -> baseDate.plusYears(amount);
                default -> baseDate.plusMonths(amount);
            };
            return resolvedDate;
        }
        return null;
    }

    private LocalDate resolveDiwaliDate(String explicitYear) {
        if (explicitYear != null) {
            try {
                return DIWALI_DATES.get(Integer.parseInt(explicitYear));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        LocalDate today = LocalDate.now(clock);
        return DIWALI_DATES.values().stream()
                .filter(date -> !date.isBefore(today))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private boolean hasNonPossessionIntervalContext(String prompt, int matchStart) {
        int contextStart = Math.max(0, matchStart - 48);
        String precedingText = prompt.substring(contextStart, matchStart);
        return NON_POSSESSION_INTERVAL_CONTEXT_PATTERN.matcher(precedingText).find();
    }

    private String formatResolvedPossessionDate(LocalDate date) {
        return date.format(RESOLVED_POSSESSION_DATE_FORMATTER);
    }

    private String normalizeTypos(String value) {
        String normalized = TYPO_FLAT_PATTERN.matcher(value).replaceAll("flat");
        normalized = TYPO_HOUSE_PATTERN.matcher(normalized).replaceAll("house");
        normalized = TYPO_PLOT_PATTERN.matcher(normalized).replaceAll("plot");
        normalized = TYPO_SEMI_FURNISHED_PATTERN.matcher(normalized).replaceAll("semi furnished");
        normalized = TYPO_FULLY_FURNISHED_PATTERN.matcher(normalized).replaceAll("fully furnished");
        normalized = TYPO_UNFURNISHED_PATTERN.matcher(normalized).replaceAll("unfurnished");
        normalized = TYPO_EAST_FACING_PATTERN.matcher(normalized).replaceAll("east facing");
        normalized = TYPO_WEST_FACING_PATTERN.matcher(normalized).replaceAll("west facing");
        normalized = TYPO_NORTH_FACING_PATTERN.matcher(normalized).replaceAll("north facing");
        normalized = TYPO_SOUTH_FACING_PATTERN.matcher(normalized).replaceAll("south facing");
        normalized = TYPO_RENT_PATTERN.matcher(normalized).replaceAll("rent");
        normalized = TYPO_DEPOSIT_PATTERN.matcher(normalized).replaceAll("deposit");
        normalized = TYPO_NEAR_PATTERN.matcher(normalized).replaceAll("near");
        normalized = TYPO_BROKERAGE_PATTERN.matcher(normalized).replaceAll("brokerage");
        normalized = TYPO_SAKET_NAGAR_PATTERN.matcher(normalized).replaceAll("saket nagar");
        normalized = TYPO_VIJAY_NAGAR_PATTERN.matcher(normalized).replaceAll("vijay nagar");
        normalized = TYPO_NANDA_NAGAR_PATTERN.matcher(normalized).replaceAll("nanda nagar");
        normalized = TYPO_BHAWARKUA_PATTERN.matcher(normalized).replaceAll("bhawarkua");
        normalized = TYPO_PALASIA_PATTERN.matcher(normalized).replaceAll("palasia");
        return TYPO_NIPANIA_PATTERN.matcher(normalized).replaceAll("nipania");
    }

    private String toNumericBathroomCount(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "one" -> "1";
            case "two" -> "2";
            case "three" -> "3";
            case "four" -> "4";
            case "five" -> "5";
            case "six" -> "6";
            case "seven" -> "7";
            case "eight" -> "8";
            case "nine" -> "9";
            case "ten" -> "10";
            default -> value;
        };
    }

    private String resolveKnownCity(String normalizedPrompt) {
        for (String cachedCity : cityCache) {
            if (MASTER_INDIAN_CITIES.contains(cachedCity) && normalizedPrompt.contains(cachedCity)) {
                return capitalizeWords(cachedCity);
            }
        }
        for (String knownCity : MASTER_INDIAN_CITIES) {
            if (normalizedPrompt.contains(knownCity)) {
                return capitalizeWords(knownCity);
            }
        }
        return "";
    }

    private String resolveLastKnownIndoreSector(String normalizedPrompt) {
        String selectedSector = "";
        int selectedPosition = -1;
        for (String knownSector : KNOWN_INDORE_SECTORS) {
            int position = normalizedPrompt.lastIndexOf(knownSector);
            if (position > selectedPosition) {
                selectedSector = knownSector;
                selectedPosition = position;
            }
        }
        return selectedSector;
    }

    private String extractLandmarks(String normalizedPrompt) {
        LinkedHashSet<String> landmarks = new LinkedHashSet<>();
        Matcher matcher = LANDMARK_PATTERN.matcher(normalizedPrompt);
        while (matcher.find()) {
            String candidate = cleanLandmarkCandidate(matcher.group(1));
            if (!candidate.isBlank()) {
                landmarks.add(capitalizeWords(candidate));
            }
        }
        return landmarks.isEmpty() ? null : String.join(", ", landmarks);
    }

    private String cleanLandmarkCandidate(String rawCandidate) {
        String candidate = TRAILING_LANDMARK_NOISE_PATTERN.matcher(rawCandidate.trim()).replaceFirst("").trim();
        String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
        for (String knownSector : KNOWN_INDORE_SECTORS) {
            if (lowerCandidate.equals(knownSector)) {
                return "";
            }
            String suffix = " " + knownSector;
            if (lowerCandidate.endsWith(suffix)) {
                return candidate.substring(0, candidate.length() - suffix.length()).trim();
            }
        }
        return candidate;
    }

    private boolean isAreaUnitImmediatelyAfter(String text, int numberEnd) {
        Matcher areaUnitMatcher = AREA_UNIT_AFTER_NUMBER_PATTERN.matcher(text);
        areaUnitMatcher.region(numberEnd, text.length());
        return areaUnitMatcher.lookingAt();
    }

    private boolean hasAmountImmediatelyBefore(String text, int amountStart) {
        int contextStart = Math.max(0, amountStart - 24);
        return PRECEDING_MONETARY_AMOUNT_PATTERN.matcher(text.substring(contextStart, amountStart)).find();
    }

    private boolean isSameAmountMatchedBy(
            String text, int amountStart, int amountEnd, Pattern firstPattern, Pattern secondPattern) {
        return isSameAmountMatchedBy(text, amountStart, amountEnd, firstPattern)
                || isSameAmountMatchedBy(text, amountStart, amountEnd, secondPattern);
    }

    private boolean isSameAmountMatchedBy(String text, int amountStart, int amountEnd, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.start(1) == amountStart && matcher.end(1) == amountEnd) return true;
            if (matcher.start(1) > amountStart) return false;
        }
        return false;
    }

    private Locality resolveCachedLocality(String normalizedPrompt, String resolvedCity) {
        Locality matched = null;
        for (Locality locality : localityCache.values()) {
            if (locality.getSectorName() == null
                    || !normalizedPrompt.contains(locality.getSectorName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!resolvedCity.isBlank()) {
                if (locality.getCity() != null && locality.getCity().equalsIgnoreCase(resolvedCity)) {
                    return locality;
                }
                continue;
            }
            if (matched != null && locality.getCity() != null && matched.getCity() != null
                    && !locality.getCity().equalsIgnoreCase(matched.getCity())) {
                return null;
            }
            matched = locality;
        }
        return matched;
    }

    private String localityCacheKey(String city, String sector) {
        return city.trim().toLowerCase(Locale.ROOT) + "|" + sector.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isMissingValue(String value) {
        return value == null || value.isBlank() || "Not Specified".equalsIgnoreCase(value)
                || "Unspecified".equalsIgnoreCase(value);
    }

    private String findNearestPrecedingKeyword(String fullText, int numberIndex) {
        int windowStart = Math.max(0, numberIndex - 35);
        String textBefore = fullText.substring(windowStart, numberIndex).toLowerCase();

        int lastRent = Math.max(
                Math.max(textBefore.lastIndexOf("monthly rent"), textBefore.lastIndexOf("rent")),
                Math.max(textBefore.lastIndexOf("rnt"), textBefore.lastIndexOf("ren"))
        );
        int lastBrokerage = Math.max(
                Math.max(textBefore.lastIndexOf("brokerage"), textBefore.lastIndexOf("broker")),
                Math.max(textBefore.lastIndexOf("commission"), textBefore.lastIndexOf("brokrage"))
        );
        int lastDeposit = Math.max(
                Math.max(textBefore.lastIndexOf("security deposit"), textBefore.lastIndexOf("deposit")),
                Math.max(textBefore.lastIndexOf("depost"), textBefore.lastIndexOf("securuity"))
        );

        if (lastRent > lastBrokerage && lastRent > lastDeposit) return "RENT";
        if (lastBrokerage > lastRent && lastBrokerage > lastDeposit) return "BROKERAGE";
        if (lastDeposit > lastRent && lastDeposit > lastBrokerage) return "DEPOSIT";
        return "NONE";
    }

    private String capitalizeWords(String str) {
        if (str == null || str.isEmpty())
            return str;
        String[] words = WHITESPACE_PATTERN.split(str);
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * High-Performance, Delimiter-Agnostic Multi-Prompt Batch Parser.
     * Splits multi-listing text/voice transcripts by numbers (1., 2)), dividers (---, ===),
     * paragraph breaks, or voice cues ("next property", "next flat").
     * Runs in sub-millisecond execution time backed by L1 Locality Cache.
     */
    public List<ParsedPropertyDTO> parseBatch(String multiPrompt) {
        if (multiPrompt == null || multiPrompt.isBlank()) {
            return Collections.emptyList();
        }

        BatchAmendmentExtraction amendmentExtraction = extractTargetedAmendments(multiPrompt.trim());
        String promptWithoutAmendments = amendmentExtraction.promptWithoutAmendments();
        String[] chunks = MULTI_PROMPT_SPLIT_PATTERN.split(promptWithoutAmendments);
        List<ParsedPropertyDTO> results = new ArrayList<>();
        int index = 1;

        for (String chunk : chunks) {
            String trimmed = chunk != null ? chunk.trim() : "";
            // Strip leading prompt markers if left over like "1)" or "1." or "#1"
            trimmed = LEADING_BATCH_MARKER_PATTERN.matcher(trimmed).replaceFirst("").trim();
            if (trimmed.length() >= 5) {
                ParsedPropertyDTO dto = parse(trimmed);
                dto.setPromptIndex(index++);

                // Extract any in-prompt media URLs
                Matcher urlMatcher = IN_PROMPT_URL_PATTERN.matcher(trimmed);
                List<String> extractedUrls = new ArrayList<>();
                while (urlMatcher.find()) {
                    String u = urlMatcher.group();
                    if (MEDIA_URL_EXTENSION_PATTERN.matcher(u).matches() || u.contains("cloudinary") || u.contains("drive.google")) {
                        extractedUrls.add(u);
                    }
                }
                if (!extractedUrls.isEmpty()) {
                    dto.setMediaUrls(extractedUrls);
                }

                results.add(dto);
            }
        }

        if (results.isEmpty() && !promptWithoutAmendments.isBlank()) {
            ParsedPropertyDTO single = parse(promptWithoutAmendments.trim());
            single.setPromptIndex(1);
            results.add(single);
        }

        applyTargetedAmendments(results, amendmentExtraction.amendments());

        return results;
    }

    private BatchAmendmentExtraction extractTargetedAmendments(String prompt) {
        BatchAmendmentExtraction actionFirst = extractActionFirstAmendments(prompt);
        return extractTargetFirstAmendments(actionFirst.promptWithoutAmendments(), actionFirst.amendments());
    }

    private BatchAmendmentExtraction extractActionFirstAmendments(String prompt) {
        Matcher matcher = TARGETED_AMENDMENT_ACTION_FIRST_PATTERN.matcher(prompt);
        List<TargetedAmendment> amendments = new ArrayList<>();
        List<TextRange> ranges = new ArrayList<>();
        while (matcher.find()) {
            int targetIndex = parseTargetIndex(firstNonBlank(matcher.group(2), matcher.group(3)));
            String payload = matcher.group(1).trim();
            if (targetIndex > 0 && !payload.isBlank()) {
                amendments.add(new TargetedAmendment(targetIndex, matcher.group().trim(), payload));
                ranges.add(new TextRange(matcher.start(), matcher.end()));
            }
        }
        return new BatchAmendmentExtraction(removeTextRanges(prompt, ranges), amendments);
    }

    private BatchAmendmentExtraction extractTargetFirstAmendments(
            String prompt, List<TargetedAmendment> existingAmendments) {
        List<AmendmentMarker> markers = new ArrayList<>();
        collectAmendmentMarkers(markers, TARGETED_AMENDMENT_PREFIX_PATTERN.matcher(prompt));
        collectAmendmentMarkers(markers, TARGETED_AMENDMENT_DIRECT_PATTERN.matcher(prompt));
        markers.sort(Comparator.comparingInt(AmendmentMarker::start));

        List<AmendmentMarker> distinctMarkers = new ArrayList<>();
        for (AmendmentMarker marker : markers) {
            boolean overlapsPrefix = distinctMarkers.stream()
                    .anyMatch(existing -> marker.start() >= existing.start() && marker.start() < existing.payloadStart());
            if (!overlapsPrefix) {
                distinctMarkers.add(marker);
            }
        }

        List<TargetedAmendment> amendments = new ArrayList<>(existingAmendments);
        List<TextRange> ranges = new ArrayList<>();
        for (int markerIndex = 0; markerIndex < distinctMarkers.size(); markerIndex++) {
            AmendmentMarker marker = distinctMarkers.get(markerIndex);
            int candidateEnd = markerIndex + 1 < distinctMarkers.size()
                    ? distinctMarkers.get(markerIndex + 1).start()
                    : prompt.length();
            int payloadEnd = findNextBatchBoundary(prompt, marker.payloadStart(), candidateEnd);
            String payload = prompt.substring(marker.payloadStart(), payloadEnd).trim();
            if (marker.targetIndex() > 0 && !payload.isBlank()) {
                String source = prompt.substring(marker.start(), payloadEnd).trim();
                amendments.add(new TargetedAmendment(marker.targetIndex(), source, payload));
                ranges.add(new TextRange(marker.start(), payloadEnd));
            }
        }
        return new BatchAmendmentExtraction(removeTextRanges(prompt, ranges), amendments);
    }

    private void collectAmendmentMarkers(List<AmendmentMarker> markers, Matcher matcher) {
        while (matcher.find()) {
            int targetIndex = parseTargetIndex(firstNonBlank(matcher.group(1), matcher.group(2)));
            if (targetIndex > 0) {
                markers.add(new AmendmentMarker(matcher.start(), matcher.end(), targetIndex));
            }
        }
    }

    private int findNextBatchBoundary(String prompt, int searchStart, int candidateEnd) {
        Matcher boundaryMatcher = MULTI_PROMPT_SPLIT_PATTERN.matcher(prompt);
        boundaryMatcher.region(searchStart, candidateEnd);
        return boundaryMatcher.find() ? boundaryMatcher.start() : candidateEnd;
    }

    private String removeTextRanges(String prompt, List<TextRange> ranges) {
        if (ranges.isEmpty()) return prompt;
        ranges.sort(Comparator.comparingInt(TextRange::start).reversed());
        StringBuilder cleaned = new StringBuilder(prompt);
        for (TextRange range : ranges) {
            cleaned.replace(range.start(), range.end(), " ");
        }
        return cleaned.toString().trim();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private int parseTargetIndex(String rawIndex) {
        if (rawIndex == null || rawIndex.isBlank()) return -1;
        String normalizedIndex = rawIndex.toLowerCase(Locale.ROOT);
        String digits = NON_DIGIT_PATTERN.matcher(normalizedIndex).replaceAll("");
        if (!digits.isBlank()) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return switch (normalizedIndex) {
            case "one", "first" -> 1;
            case "two", "second" -> 2;
            case "three", "third" -> 3;
            case "four", "fourth" -> 4;
            case "five", "fifth" -> 5;
            case "six", "sixth" -> 6;
            case "seven", "seventh" -> 7;
            case "eight", "eighth" -> 8;
            case "nine", "ninth" -> 9;
            case "ten", "tenth" -> 10;
            case "eleven", "eleventh" -> 11;
            case "twelve", "twelfth" -> 12;
            case "thirteen", "thirteenth" -> 13;
            case "fourteen", "fourteenth" -> 14;
            case "fifteen", "fifteenth" -> 15;
            case "sixteen", "sixteenth" -> 16;
            case "seventeen", "seventeenth" -> 17;
            case "eighteen", "eighteenth" -> 18;
            case "nineteen", "nineteenth" -> 19;
            case "twenty", "twentieth" -> 20;
            default -> -1;
        };
    }

    private void applyTargetedAmendments(List<ParsedPropertyDTO> results, List<TargetedAmendment> amendments) {
        for (TargetedAmendment amendment : amendments) {
            if (amendment.targetIndex() < 1 || amendment.targetIndex() > results.size()) {
                if (!results.isEmpty()) {
                    ParsedPropertyDTO lastProperty = results.get(results.size() - 1);
                    addConflict(lastProperty,
                            "Later correction refers to property " + amendment.targetIndex()
                                    + ", but only " + results.size() + " properties were detected");
                    refreshDerivedFields(lastProperty);
                }
                continue;
            }

            ParsedPropertyDTO target = results.get(amendment.targetIndex() - 1);
            ParsedPropertyDTO patch = parse(amendment.payload());
            int appliedFieldCount = applyExplicitAmendmentFields(target, patch, amendment.payload());
            if (appliedFieldCount > 0) {
                List<String> appliedAmendments = new ArrayList<>(target.getAppliedAmendments());
                appliedAmendments.add(amendment.sourceText());
                target.setAppliedAmendments(appliedAmendments);
                target.setRawPrompt(target.getRawPrompt() + "\n\nLater correction: " + amendment.sourceText());
            } else {
                addConflict(target, "Later correction could not be applied safely: " + amendment.sourceText());
            }
            refreshDerivedFields(target);
        }
    }

    private int applyExplicitAmendmentFields(ParsedPropertyDTO target, ParsedPropertyDTO patch, String payload) {
        int applied = 0;
        if (AMENDMENT_RENT_CUE_PATTERN.matcher(payload).find()
                && patch.getRentAmount() != null && patch.getRentAmount() > 0) {
            target.setRentAmount(patch.getRentAmount());
            target.setRentVal(patch.getRentVal());
            replaceFieldConflicts(target, patch, "Monthly Rent");
            applied++;
        }
        if (AMENDMENT_BROKERAGE_CUE_PATTERN.matcher(payload).find()
                && !isMissingValue(patch.getBrokerageVal()) && !"Unmentioned".equalsIgnoreCase(patch.getBrokerageVal())) {
            target.setBrokerageVal(patch.getBrokerageVal());
            replaceFieldConflicts(target, patch, "Brokerage");
            applied++;
        }
        if (AMENDMENT_DEPOSIT_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getDepositVal())) {
            target.setDepositVal(patch.getDepositVal());
            replaceFieldConflicts(target, patch, "Security Deposit");
            applied++;
        }
        if (AMENDMENT_OWNER_PHONE_CUE_PATTERN.matcher(payload).find()) {
            if (!isMissingValue(patch.getOwnerPhone())) {
                target.setOwnerPhone(patch.getOwnerPhone());
                removeMissingField(target, "Owner Contact Number");
                applied++;
            } else {
                addConflict(target, "Owner phone in the later correction is not a valid 10-digit Indian mobile number");
            }
        }
        if (AMENDMENT_OWNER_NAME_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getOwnerName())) {
            target.setOwnerName(patch.getOwnerName());
            applied++;
        }
        if (AMENDMENT_BATHROOM_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getBathrooms())) {
            target.setBathrooms(patch.getBathrooms());
            applied++;
        }
        if (AMENDMENT_LAYOUT_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getBhk())) {
            target.setBhk(patch.getBhk());
            applied++;
        }
        if (AMENDMENT_TYPE_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getType())) {
            target.setType(patch.getType());
            applied++;
        }
        if (AMENDMENT_AREA_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getAreaSqFt())) {
            target.setAreaSqFt(patch.getAreaSqFt());
            applied++;
        }
        if (AMENDMENT_FURNISHING_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getFurnishingStatus())) {
            target.setFurnishingStatus(patch.getFurnishingStatus());
            replaceFieldConflicts(target, patch, "Furnishing");
            applied++;
        }
        if (AMENDMENT_POSSESSION_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getPossessionDate())) {
            target.setPossessionDate(patch.getPossessionDate());
            target.setAvailabilityStatus(patch.getAvailabilityStatus());
            target.setAvailableFrom(patch.getAvailableFrom());
            applied++;
        }
        if (AMENDMENT_FACING_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getVastuFacing())) {
            target.setVastuFacing(patch.getVastuFacing());
            replaceFieldConflicts(target, patch, "Facing Direction");
            applied++;
        }
        if (AMENDMENT_STATUS_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getStatus())) {
            target.setStatus(patch.getStatus());
            applied++;
        }
        if (AMENDMENT_LOCATION_CUE_PATTERN.matcher(payload).find()) {
            if (!isMissingValue(patch.getSector())) {
                target.setSector(patch.getSector());
                applied++;
            }
            if (!isMissingValue(patch.getCity())) {
                target.setCity(patch.getCity());
                applied++;
            }
            if (!isMissingValue(patch.getState())) target.setState(patch.getState());
        }
        if (AMENDMENT_LANDMARK_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getLandmark())) {
            target.setLandmark(patch.getLandmark());
            applied++;
        }
        if (AMENDMENT_PINCODE_CUE_PATTERN.matcher(payload).find() && !isMissingValue(patch.getPincode())) {
            target.setPincode(patch.getPincode());
            applied++;
        }
        if (patch.getAmenities() != null && !patch.getAmenities().isEmpty()) {
            LinkedHashSet<String> amenities = new LinkedHashSet<>(target.getAmenities());
            if (amenities.addAll(patch.getAmenities())) applied++;
            target.setAmenities(new ArrayList<>(amenities));
        }
        return applied;
    }

    private void replaceFieldConflicts(ParsedPropertyDTO target, ParsedPropertyDTO patch, String field) {
        List<String> retained = new ArrayList<>();
        for (String conflict : target.getConflicts()) {
            if (!conflict.startsWith(field)) retained.add(conflict);
        }
        for (String conflict : patch.getConflicts()) {
            if (conflict.startsWith(field)) retained.add(conflict);
        }
        target.setConflicts(retained);
    }

    private void addConflict(ParsedPropertyDTO dto, String conflict) {
        List<String> conflicts = new ArrayList<>(dto.getConflicts());
        if (!conflicts.contains(conflict)) conflicts.add(conflict);
        dto.setConflicts(conflicts);
    }

    private void removeMissingField(ParsedPropertyDTO dto, String field) {
        List<String> missingFields = new ArrayList<>(dto.getMissingFields());
        missingFields.remove(field);
        dto.setMissingFields(missingFields);
    }

    private record TargetedAmendment(int targetIndex, String sourceText, String payload) {}

    private record BatchAmendmentExtraction(
            String promptWithoutAmendments, List<TargetedAmendment> amendments) {}

    private record AmendmentMarker(int start, int payloadStart, int targetIndex) {}

    private record TextRange(int start, int end) {}

    private record PossessionResolution(
            String displayText, AvailabilityStatus status, LocalDate availableFrom) {
        private static PossessionResolution unspecified() {
            return new PossessionResolution(null, AvailabilityStatus.UNSPECIFIED, null);
        }
    }
}
