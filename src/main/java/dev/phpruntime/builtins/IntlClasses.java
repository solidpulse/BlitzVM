package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of the intl extension the framework and its dependencies reach: the Locale
 * identifier helpers. Formatting and collation classes are not provided.
 */
public final class IntlClasses {
    private IntlClasses() {
    }

    private static final String DEFAULT_LOCALE = "en_US_POSIX";

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        method(language, methods, "getDefault", 0, 0, (ctx, self, args) -> {
            String configured = ContextState.of(ctx).intlDefaultLocale;
            return configured == null ? DEFAULT_LOCALE : configured;
        });
        method(language, methods, "setDefault", 1, 1, (ctx, self, args) -> {
            ContextState.of(ctx).intlDefaultLocale = PhpOps.toStr(args[0]);
            return true;
        });
        for (String name : List.of("getPrimaryLanguage", "getScript", "getRegion")) {
            method(language, methods, name, 1, 1, (ctx, self, args) -> {
                Map<String, String> parsed = parse(PhpOps.toStr(args[0]));
                if (parsed == null) {
                    return false;
                }
                return switch (name) {
                    case "getPrimaryLanguage" -> parsed.getOrDefault("language", "");
                    case "getScript" -> parsed.getOrDefault("script", "");
                    default -> parsed.getOrDefault("region", "");
                };
            });
        }
        method(language, methods, "getAllVariants", 1, 1, (ctx, self, args) -> {
            Map<String, String> parsed = parse(PhpOps.toStr(args[0]));
            if (parsed == null) {
                return false;
            }
            PhpArray result = new PhpArray();
            for (int i = 0; ; i++) {
                String variant = parsed.get("variant" + i);
                if (variant == null) {
                    break;
                }
                result.append(variant);
            }
            return result;
        });
        method(language, methods, "parseLocale", 1, 1, (ctx, self, args) -> {
            Map<String, String> parsed = parse(PhpOps.toStr(args[0]));
            if (parsed == null) {
                return false;
            }
            PhpArray result = new PhpArray();
            parsed.forEach((key, value) -> result.put(key, value));
            return result;
        });
        method(language, methods, "composeLocale", 1, 1, (ctx, self, args) -> {
            if (!(args[0] instanceof PhpArray subtags)) {
                throw new PhpError(PhpError.TYPE_ERROR, "Locale::composeLocale(): Argument #1 ($subtags)"
                        + " must be of type array, " + PhpOps.typeDescription(args[0]) + " given", null);
            }
            StringBuilder sb = new StringBuilder();
            for (String key : List.of("language", "script", "region")) {
                Object value = subtags.get(key);
                if (value == null) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('_');
                }
                sb.append(PhpOps.toStr(value));
            }
            for (int i = 0; ; i++) {
                Object variant = subtags.get("variant" + i);
                if (variant == null) {
                    break;
                }
                if (!sb.isEmpty()) {
                    sb.append('_');
                }
                sb.append(PhpOps.toStr(variant));
            }
            return sb.isEmpty() ? false : sb.toString();
        });
        method(language, methods, "canonicalize", 1, 1, (ctx, self, args) -> {
            Map<String, String> parsed = parse(PhpOps.toStr(args[0]));
            if (parsed == null) {
                return false;
            }
            StringBuilder sb = new StringBuilder();
            for (String key : List.of("language", "script", "region")) {
                String value = parsed.get(key);
                if (value == null) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('_');
                }
                sb.append(value);
            }
            return sb.isEmpty() ? false : sb.toString();
        });

        List<PhpClassTemplate.ConstantDecl> constants = List.of(
                constant("ACTUAL_LOCALE", 0L),
                constant("VALID_LOCALE", 1L),
                constant("DEFAULT_LOCALE", PhpNull.INSTANCE),
                constant("LANG_TAG", "language"),
                constant("EXTLANG_TAG", "extlang"),
                constant("SCRIPT_TAG", "script"),
                constant("REGION_TAG", "region"),
                constant("VARIANT_TAG", "variant"),
                constant("GRANDFATHERED_LANG_TAG", "grandfathered"),
                constant("PRIVATE_TAG", "private"));
        out.put("locale", new PhpClassTemplate(language.nextTemplateId(), "Locale", false, false, false,
                false, null, List.of(), List.of(), constants, methods));

        buildTimeZone(language, out);
        buildCalendar(language, out);
        buildNumberFormatter(language, out);
    }

    // ---------------------------------------------------------------- NumberFormatter

    private static final class NumberState implements dev.phpruntime.runtime.PhpNativeState {
        final String locale;
        final long style;
        int minFractionDigits;
        int maxFractionDigits;
        String defaultRuleset;
        int errorCode;
        String errorMessage = "U_ZERO_ERROR";

        NumberState(String locale, long style, int minFractionDigits, int maxFractionDigits,
                    String defaultRuleset) {
            this.locale = locale;
            this.style = style;
            this.minFractionDigits = minFractionDigits;
            this.maxFractionDigits = maxFractionDigits;
            this.defaultRuleset = defaultRuleset;
        }

        String locale() { return locale; }
        long style() { return style; }
        int minFractionDigits() { return minFractionDigits; }
        int maxFractionDigits() { return maxFractionDigits; }
        String defaultRuleset() { return defaultRuleset; }

        @Override
        public dev.phpruntime.runtime.PhpNativeState copy() {
            NumberState copy = new NumberState(locale, style, minFractionDigits, maxFractionDigits, defaultRuleset);
            copy.errorCode = errorCode;
            copy.errorMessage = errorMessage;
            return copy;
        }
    }

    private static NumberState numberState(PhpObject object) {
        if (object.nativeState() instanceof NumberState state) {
            return state;
        }
        throw new PhpError("NumberFormatter object is not initialized");
    }

    private static void buildNumberFormatter(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        instanceMethod(language, methods, "__construct", 2, 3, (ctx, self, args) -> {
            long style = PhpOps.toLong(args[1]);
            int min = style == 2 ? 2 : 0;
            int max = style == 2 ? 2 : 3;
            self.setNativeState(new NumberState(PhpOps.toStr(args[0]), style, min, max, null));
            return PhpNull.INSTANCE;
        });
        instanceMethod(language, methods, "format", 1, 2, (ctx, self, args) -> {
            NumberState state = numberState(self);
            double value = PhpOps.toDouble(args[0]);
            return formatNumber(state, value, null);
        });
        instanceMethod(language, methods, "formatCurrency", 2, 2, (ctx, self, args) -> {
            NumberState state = numberState(self);
            return formatNumber(state, PhpOps.toDouble(args[0]), PhpOps.toStr(args[1]));
        });
        instanceMethod(language, methods, "parse", 1, 3, (ctx, self, args) -> {
            NumberState state = numberState(self);
            long type = args.length > 1 ? PhpOps.toLong(args[1]) : 3;
            if (type == 4) {
                throw new PhpError(PhpError.VALUE_ERROR, "NumberFormatter::parse(): Argument #2 ($type) cannot be "
                        + "NumberFormatter::TYPE_CURRENCY constant, use NumberFormatter::parseCurrency() method instead", null);
            }
            if (type != 1 && type != 2 && type != 3) {
                throw new PhpError(PhpError.VALUE_ERROR, "NumberFormatter::parse(): Argument #2 ($type) must be a "
                        + "NumberFormatter::TYPE_* constant", null);
            }
            state.errorCode = 0;
            state.errorMessage = "U_ZERO_ERROR";
            String text = PhpOps.toStr(args[0]);
            int end = 0;
            boolean digits = false;
            boolean dot = false;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c >= '0' && c <= '9') {
                    digits = true;
                    end++;
                } else if (c == ',') {
                    end++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    end++;
                } else if ((c == '-' || c == '+') && end == 0) {
                    end++;
                } else {
                    break;
                }
            }
            if (!digits) {
                state.errorCode = 9;
                state.errorMessage = "NumberFormatter::parse(): Number parsing failed: U_PARSE_ERROR";
                return false;
            }
            String normalized = text.substring(0, end).replace(",", "");
            try {
                if (type == 1 || type == 2) {
                    return Long.parseLong(normalized);
                }
                return Double.parseDouble(normalized);
            } catch (NumberFormatException invalid) {
                state.errorCode = 9;
                state.errorMessage = "NumberFormatter::parse(): Number parsing failed: U_PARSE_ERROR";
                return false;
            }
        });
        instanceMethod(language, methods, "setAttribute", 2, 2, (ctx, self, args) -> {
            NumberState state = numberState(self);
            long attribute = PhpOps.toLong(args[0]);
            int value = (int) PhpOps.toLong(args[1]);
            int min = state.minFractionDigits();
            int max = state.maxFractionDigits();
            if (attribute == 6) {
                max = value;
            } else if (attribute == 8) {
                min = value;
                max = value;
            } else if (attribute == 7) {
                min = value;
            } else if (attribute == 9) {
                // MULTIPLIER: only the defaults matter for the pages, leave them.
            }
            self.setNativeState(new NumberState(state.locale(), state.style(), min, max,
                    state.defaultRuleset()));
            return true;
        });
        instanceMethod(language, methods, "getAttribute", 1, 1, (ctx, self, args) -> {
            NumberState state = numberState(self);
            return switch ((int) PhpOps.toLong(args[0])) {
                case 6 -> (long) state.maxFractionDigits();
                case 7 -> (long) state.minFractionDigits();
                case 8 -> (long) state.maxFractionDigits();
                default -> 0L;
            };
        });
        instanceMethod(language, methods, "setTextAttribute", 2, 2, (ctx, self, args) -> {
            NumberState state = numberState(self);
            String ruleset = ((int) PhpOps.toLong(args[0])) == 6 ? PhpOps.toStr(args[1]) : state.defaultRuleset();
            self.setNativeState(new NumberState(state.locale(), state.style(), state.minFractionDigits(),
                    state.maxFractionDigits(), ruleset));
            return true;
        });
        instanceMethod(language, methods, "getTextAttribute", 1, 1, (ctx, self, args) -> {
            NumberState state = numberState(self);
            return ((int) PhpOps.toLong(args[0])) == 6 && state.defaultRuleset() != null
                    ? state.defaultRuleset() : false;
        });
        instanceMethod(language, methods, "getErrorCode", 0, 0, (ctx, self, args) -> (long) numberState(self).errorCode);
        instanceMethod(language, methods, "getErrorMessage", 0, 0, (ctx, self, args) -> numberState(self).errorMessage);

        List<PhpClassTemplate.ConstantDecl> constants = List.of(
                constant("PATTERN_DECIMAL", 0L), constant("DECIMAL", 1L), constant("CURRENCY", 2L),
                constant("PERCENT", 3L), constant("SCIENTIFIC", 4L), constant("SPELLOUT", 5L),
                constant("ORDINAL", 6L), constant("DURATION", 7L), constant("PATTERN_RULEBASED", 9L),
                constant("CURRENCY_ISO", 10L), constant("CURRENCY_PLURAL", 11L),
                constant("CURRENCY_ACCOUNTING", 12L), constant("CASH_CURRENCY", 13L),
                constant("DECIMAL_COMPACT_SHORT", 14L), constant("DECIMAL_COMPACT_LONG", 15L),
                constant("CURRENCY_STANDARD", 16L), constant("DEFAULT_STYLE", 1L),
                constant("ROUND_CEILING", 0L), constant("ROUND_FLOOR", 1L), constant("ROUND_DOWN", 2L),
                constant("ROUND_UP", 3L), constant("ROUND_TOWARD_ZERO", 2L),
                constant("ROUND_AWAY_FROM_ZERO", 3L), constant("ROUND_HALFEVEN", 4L),
                constant("ROUND_HALFODD", 8L), constant("ROUND_HALFDOWN", 5L), constant("ROUND_HALFUP", 6L),
                constant("PAD_BEFORE_PREFIX", 0L), constant("PAD_AFTER_PREFIX", 1L),
                constant("PAD_BEFORE_SUFFIX", 2L), constant("PAD_AFTER_SUFFIX", 3L),
                constant("PARSE_INT_ONLY", 0L), constant("GROUPING_USED", 1L),
                constant("DECIMAL_ALWAYS_SHOWN", 2L), constant("MAX_INTEGER_DIGITS", 3L),
                constant("MIN_INTEGER_DIGITS", 4L), constant("INTEGER_DIGITS", 5L),
                constant("MAX_FRACTION_DIGITS", 6L), constant("MIN_FRACTION_DIGITS", 7L),
                constant("FRACTION_DIGITS", 8L), constant("MULTIPLIER", 9L),
                constant("GROUPING_SIZE", 10L), constant("ROUNDING_MODE", 11L),
                constant("ROUNDING_INCREMENT", 12L), constant("FORMAT_WIDTH", 13L),
                constant("PADDING_POSITION", 14L), constant("SECONDARY_GROUPING_SIZE", 15L),
                constant("SIGNIFICANT_DIGITS_USED", 16L), constant("MIN_SIGNIFICANT_DIGITS", 17L),
                constant("MAX_SIGNIFICANT_DIGITS", 18L), constant("LENIENT_PARSE", 19L),
                constant("POSITIVE_PREFIX", 0L), constant("POSITIVE_SUFFIX", 1L),
                constant("NEGATIVE_PREFIX", 2L), constant("NEGATIVE_SUFFIX", 3L),
                constant("PADDING_CHARACTER", 4L), constant("CURRENCY_CODE", 5L),
                constant("DEFAULT_RULESET", 6L), constant("PUBLIC_RULESETS", 7L),
                constant("DECIMAL_SEPARATOR_SYMBOL", 0L), constant("GROUPING_SEPARATOR_SYMBOL", 1L),
                constant("PATTERN_SEPARATOR_SYMBOL", 2L), constant("PERCENT_SYMBOL", 3L),
                constant("ZERO_DIGIT_SYMBOL", 4L), constant("DIGIT_SYMBOL", 5L),
                constant("MINUS_SIGN_SYMBOL", 6L), constant("PLUS_SIGN_SYMBOL", 7L),
                constant("CURRENCY_SYMBOL", 8L), constant("INTL_CURRENCY_SYMBOL", 9L),
                constant("MONETARY_SEPARATOR_SYMBOL", 10L), constant("EXPONENTIAL_SYMBOL", 11L),
                constant("PERMILL_SYMBOL", 12L), constant("PAD_ESCAPE_SYMBOL", 13L),
                constant("INFINITY_SYMBOL", 14L), constant("NAN_SYMBOL", 15L),
                constant("SIGNIFICANT_DIGIT_SYMBOL", 16L), constant("MONETARY_GROUPING_SEPARATOR_SYMBOL", 17L),
                constant("TYPE_DEFAULT", 0L), constant("TYPE_INT32", 1L), constant("TYPE_INT64", 2L),
                constant("TYPE_DOUBLE", 3L), constant("TYPE_CURRENCY", 4L));
        out.put("numberformatter", new PhpClassTemplate(language.nextTemplateId(), "NumberFormatter", false,
                false, false, false, null, List.of(), List.of(), constants, methods));
    }

    private static String formatNumber(NumberState state, double number, String currency) {
        if (Double.isNaN(number)) {
            return "NaN";
        }
        boolean percent = currency == null && state.style() == 3;
        if (percent) {
            number *= 100;
        }
        int max = state.maxFractionDigits();
        int min = Math.min(state.minFractionDigits(), max);
        java.math.BigDecimal value = java.math.BigDecimal.valueOf(number)
                .setScale(max, java.math.RoundingMode.HALF_UP);
        String plain = value.toPlainString();
        boolean negative = plain.startsWith("-");
        if (negative) {
            plain = plain.substring(1);
        }
        int dot = plain.indexOf('.');
        String integer = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot + 1);
        while (fraction.length() > min && fraction.endsWith("0")) {
            fraction = fraction.substring(0, fraction.length() - 1);
        }
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(integer.charAt(i));
        }
        if (!fraction.isEmpty()) {
            grouped.append('.').append(fraction);
        }
        String symbol = "";
        if (currency != null) {
            symbol = switch (currency.toUpperCase(java.util.Locale.ROOT)) {
                case "USD" -> "$";
                case "EUR" -> "\u20ac";
                case "GBP" -> "\u00a3";
                case "JPY" -> "\u00a5";
                default -> currency + " ";
            };
        }
        String suffix = percent ? "%" : "";
        return (negative ? "-" : "") + symbol + grouped + suffix;
    }

    // ---------------------------------------------------------------- IntlTimeZone

    private record TimeZoneState(String id, int rawOffset) implements dev.phpruntime.runtime.PhpNativeState {
        @Override
        public dev.phpruntime.runtime.PhpNativeState copy() {
            return this;
        }
    }

    private static java.time.ZoneId zone(String id) {
        try {
            return java.time.ZoneId.of(id);
        } catch (java.time.DateTimeException e) {
            return java.time.ZoneOffset.UTC;
        }
    }

    private static TimeZoneState timeZoneState(PhpObject object) {
        return object.nativeState() instanceof TimeZoneState state
                ? state : new TimeZoneState("Etc/Unknown", 0);
    }

    private static void buildTimeZone(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        method(language, methods, "createTimeZone", 1, 1, (ctx, self, args) -> {
            String id = PhpOps.toStr(args[0]);
            java.time.ZoneId zone;
            int rawOffset;
            try {
                zone = java.time.ZoneId.of(id);
            } catch (java.time.DateTimeException e) {
                return newReflectionObject(ctx, "IntlTimeZone", new TimeZoneState("Etc/Unknown", 0));
            }
            rawOffset = (int) zone.getRules().getStandardOffset(java.time.Instant.now()).getTotalSeconds() * 1000;
            return newReflectionObject(ctx, "IntlTimeZone", new TimeZoneState(id, rawOffset));
        });
        method(language, methods, "getUnknown", 0, 0, (ctx, self, args) ->
                newReflectionObject(ctx, "IntlTimeZone", new TimeZoneState("Etc/Unknown", 0)));
        instanceMethod(language, methods, "getID", 0, 0, (ctx, self, args) -> timeZoneState(self).id());
        instanceMethod(language, methods, "getRawOffset", 0, 0, (ctx, self, args) -> (long) timeZoneState(self).rawOffset());
        instanceMethod(language, methods, "getOffset", 4, 4, (ctx, self, args) -> {
            String id = timeZoneState(self).id();
            double date = PhpOps.toDouble(args[0]);
            boolean local = PhpOps.toBool(args[1]);
            long millis = (long) date;
            java.time.ZoneId zone = zone(id);
            java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
            if (!local) {
                return (long) zone.getRules().getOffset(instant).getTotalSeconds() * 1000;
            }
            java.time.LocalDateTime localDateTime =
                    java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
            java.time.zone.ZoneOffsetTransition transition = zone.getRules().getTransition(localDateTime);
            return (long) (transition != null ? transition.getOffsetBefore() : zone.getRules().getOffset(instant))
                    .getTotalSeconds() * 1000;
        });
        out.put("intltimezone", new PhpClassTemplate(language.nextTemplateId(), "IntlTimeZone", false,
                false, false, false, null, List.of(), List.of(), List.of(), methods));
    }

    // ---------------------------------------------------------------- IntlCalendar

    private record CalendarState(String zoneId, long millis, String locale)
            implements dev.phpruntime.runtime.PhpNativeState {
        @Override
        public dev.phpruntime.runtime.PhpNativeState copy() {
            return this;
        }
    }

    private static CalendarState calendarState(PhpObject object) {
        if (object.nativeState() instanceof CalendarState state) {
            return state;
        }
        throw new PhpError("IntlCalendar object is not initialized");
    }

    private static java.time.ZonedDateTime zoned(CalendarState state) {
        return java.time.Instant.ofEpochMilli(state.millis()).atZone(zone(state.zoneId()));
    }

    private static void buildCalendar(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        method(language, methods, "createInstance", 0, 2, (ctx, self, args) -> {
            String zoneId = "UTC";
            if (args.length > 0 && args[0] != PhpNull.INSTANCE) {
                if (args[0] instanceof PhpObject object && object.nativeState() instanceof TimeZoneState tz) {
                    zoneId = tz.id();
                } else {
                    zoneId = PhpOps.toStr(args[0]);
                }
            }
            String locale = args.length > 1 && args[1] != PhpNull.INSTANCE ? PhpOps.toStr(args[1]) : "en_US_POSIX";
            return newReflectionObject(ctx, "IntlCalendar",
                    new CalendarState(zoneId, System.currentTimeMillis(), locale));
        });
        instanceMethod(language, methods, "setTime", 1, 1, (ctx, self, args) -> {
            CalendarState state = calendarState(self);
            long millis = (long) PhpOps.toDouble(args[0]);
            self.setNativeState(new CalendarState(state.zoneId(), millis, state.locale()));
            return true;
        });
        instanceMethod(language, methods, "setDateTime", 6, 6, (ctx, self, args) -> {
            CalendarState state = calendarState(self);
            int year = (int) PhpOps.toLong(args[0]);
            int month = (int) PhpOps.toLong(args[1]);
            int day = (int) PhpOps.toLong(args[2]);
            int hour = (int) PhpOps.toLong(args[3]);
            int minute = (int) PhpOps.toLong(args[4]);
            int second = (int) PhpOps.toLong(args[5]);
            java.time.LocalDateTime value = java.time.LocalDate.of(year, 1, 1)
                    .plusMonths(month)
                    .plusDays(day - 1L)
                    .atStartOfDay()
                    .plusHours(hour)
                    .plusMinutes(minute)
                    .plusSeconds(second);
            long millis = value.atZone(zone(state.zoneId())).toInstant().toEpochMilli();
            self.setNativeState(new CalendarState(state.zoneId(), millis, state.locale()));
            return PhpNull.INSTANCE;
        });
        instanceMethod(language, methods, "get", 1, 1, (ctx, self, args) -> {
            long field = PhpOps.toLong(args[0]);
            if (field < 0 || field > 23) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "IntlCalendar::get(): Argument #1 ($field) must be a valid field", null);
            }
            java.time.ZonedDateTime date = zoned(calendarState(self));
            return switch ((int) field) {
                case 0 -> 1L;
                case 1, 17, 19 -> (long) date.getYear();
                case 2 -> (long) date.getMonthValue() - 1;
                case 3 -> (long) date.get(java.time.temporal.WeekFields.of(java.util.Locale.US).weekOfWeekBasedYear());
                case 4 -> (long) ((date.getDayOfMonth() - 1) / 7 + 1);
                case 5 -> (long) date.getDayOfMonth();
                case 6 -> (long) date.getDayOfYear();
                case 7 -> (long) (date.getDayOfWeek().getValue() % 7 + 1);
                case 8 -> (long) ((date.getDayOfMonth() - 1) / 7 + 1);
                case 9 -> (long) (date.getHour() / 12);
                case 10 -> (long) (date.getHour() % 12);
                case 11 -> (long) date.getHour();
                case 12 -> (long) date.getMinute();
                case 13 -> (long) date.getSecond();
                case 14 -> (long) (date.getNano() / 1_000_000);
                case 15 -> (long) date.getOffset().getTotalSeconds() * 1000;
                case 16 -> (long) zone(calendarState(self).zoneId()).getRules()
                        .getDaylightSavings(date.toInstant()).getSeconds() * 1000;
                case 18 -> (long) (date.getDayOfWeek().getValue() % 7 + 1);
                case 20 -> date.toLocalDate().toEpochDay() + 2440588L;
                case 21 -> (long) ((date.getHour() * 3600L + date.getMinute() * 60L + date.getSecond()) * 1000L
                        + date.getNano() / 1_000_000);
                case 22 -> false;
                default -> false;
            };
        });
        instanceMethod(language, methods, "getTime", 0, 0, (ctx, self, args) -> (double) calendarState(self).millis());
        instanceMethod(language, methods, "getTimeZone", 0, 0, (ctx, self, args) -> {
            String id = calendarState(self).zoneId();
            int rawOffset = (int) zone(id).getRules().getStandardOffset(java.time.Instant.now()).getTotalSeconds() * 1000;
            return newReflectionObject(ctx, "IntlTimeZone", new TimeZoneState(id, rawOffset));
        });
        instanceMethod(language, methods, "getType", 0, 0, (ctx, self, args) -> "gregorian");
        instanceMethod(language, methods, "getLocale", 1, 1, (ctx, self, args) -> {
            long type = PhpOps.toLong(args[0]);
            return type == 0 ? "en" : calendarState(self).locale();
        });
        instanceMethod(language, methods, "getFirstDayOfWeek", 0, 0, (ctx, self, args) -> 1L);
        instanceMethod(language, methods, "getMinimalDaysInFirstWeek", 0, 0, (ctx, self, args) -> 1L);
        instanceMethod(language, methods, "isLenient", 0, 0, (ctx, self, args) -> true);
        instanceMethod(language, methods, "setLenient", 1, 1, (ctx, self, args) -> PhpNull.INSTANCE);

        List<PhpClassTemplate.ConstantDecl> constants = List.of(
                constant("FIELD_ERA", 0L), constant("FIELD_YEAR", 1L), constant("FIELD_MONTH", 2L),
                constant("FIELD_WEEK_OF_YEAR", 3L), constant("FIELD_WEEK_OF_MONTH", 4L),
                constant("FIELD_DATE", 5L), constant("FIELD_DAY_OF_MONTH", 5L),
                constant("FIELD_DAY_OF_YEAR", 6L), constant("FIELD_DAY_OF_WEEK", 7L),
                constant("FIELD_DAY_OF_WEEK_IN_MONTH", 8L), constant("FIELD_AM_PM", 9L),
                constant("FIELD_HOUR", 10L), constant("FIELD_HOUR_OF_DAY", 11L),
                constant("FIELD_MINUTE", 12L), constant("FIELD_SECOND", 13L),
                constant("FIELD_MILLISECOND", 14L), constant("FIELD_ZONE_OFFSET", 15L),
                constant("FIELD_DST_OFFSET", 16L), constant("FIELD_YEAR_WOY", 17L),
                constant("FIELD_DOW_LOCAL", 18L), constant("FIELD_EXTENDED_YEAR", 19L),
                constant("FIELD_JULIAN_DAY", 20L), constant("FIELD_MILLISECONDS_IN_DAY", 21L),
                constant("FIELD_IS_LEAP_MONTH", 22L), constant("FIELD_FIELD_COUNT", 24L),
                constant("DOW_SUNDAY", 1L), constant("DOW_MONDAY", 2L), constant("DOW_TUESDAY", 3L),
                constant("DOW_WEDNESDAY", 4L), constant("DOW_THURSDAY", 5L), constant("DOW_FRIDAY", 6L),
                constant("DOW_SATURDAY", 7L), constant("DOW_TYPE_WEEKDAY", 0L), constant("DOW_TYPE_WEEKEND", 1L),
                constant("DOW_TYPE_WEEKEND_OFFSET", 2L), constant("DOW_TYPE_WEEKEND_CEASE", 3L),
                constant("WALLTIME_FIRST", 1L), constant("WALLTIME_LAST", 0L), constant("WALLTIME_NEXT_VALID", 2L));
        out.put("intlcalendar", new PhpClassTemplate(language.nextTemplateId(), "IntlCalendar", false,
                false, false, false, null, List.of(), List.of(), constants, methods));
    }

    private static PhpObject newReflectionObject(PhpContext ctx, String className,
                                                 dev.phpruntime.runtime.PhpNativeState state) {
        dev.phpruntime.runtime.PhpClass cls = ctx.lookupClass(className);
        if (cls == null) {
            throw new PhpError("Intl class " + className + " is not available");
        }
        PhpObject object = new PhpObject(cls, ctx.nextObjectId());
        object.setNativeState(state);
        return object;
    }

    private interface Impl {
        Object invoke(PhpContext ctx, PhpObject self, Object[] args);
    }

    private static void method(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> methods,
                               String name, int min, int max, Impl impl) {
        String lower = PhpStrings.lower(name);
        methods.put(lower, new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name),
                Visibility.PUBLIC, true, false, false,
                new PhpExceptions.BuiltinMethodRootNode(language, name, (ctx, self, args) -> {
                    requireArgCount("Locale::" + name, args, min, max);
                    return impl.invoke(ctx, self, args);
                }).getCallTarget()));
    }

    private static void instanceMethod(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> methods,
                                       String name, int min, int max, Impl impl) {
        String lower = PhpStrings.lower(name);
        methods.put(lower, new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name),
                Visibility.PUBLIC, false, false, false,
                new PhpExceptions.BuiltinMethodRootNode(language, name, (ctx, self, args) -> {
                    requireArgCount(name + "()", args, min, max);
                    return impl.invoke(ctx, self, args);
                }).getCallTarget()));
    }

    private static void requireArgCount(String function, Object[] args, int min, int max) {
        if (args.length < min || (max >= 0 && args.length > max)) {
            String expectation = min == max ? "exactly " + min : "at least " + min;
            throw new PhpError(PhpError.ARGUMENT_COUNT, function + "() expects " + expectation
                    + " argument" + (min == 1 ? "" : "s") + ", " + args.length + " given", null);
        }
    }

    private static PhpClassTemplate.ConstantDecl constant(String name, Object value) {
        return new PhpClassTemplate.ConstantDecl(name, Visibility.PUBLIC, false, value, null);
    }

    /**
     * Splits an ICU locale identifier into the keys PHP's Locale::parseLocale reports. Returns
     * null when the identifier cannot be parsed at all; an empty identifier parses as the
     * default locale.
     */
    static Map<String, String> parse(String locale) {
        String value = locale;
        if (value.isEmpty()) {
            value = DEFAULT_LOCALE;
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(0, at);
        }
        int dot = value.indexOf('.');
        if (dot >= 0) {
            value = value.substring(0, dot);
        }
        if (value.startsWith("i-") || value.startsWith("I-")) {
            if (value.equalsIgnoreCase("i-klingon")) {
                return Map.of("grandfathered", "i-klingon");
            }
            return null;
        }
        String[] parts = value.split("[-_]", -1);
        Map<String, String> out = new LinkedHashMap<>();
        int variant = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i == 0) {
                if (part.equalsIgnoreCase("x")) {
                    String rest = String.join("_", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                    out.put("private0", rest);
                    return out;
                }
                out.put("language", PhpStrings.lower(part));
            } else if (part.length() == 4 && part.chars().allMatch(Character::isLetter)) {
                out.put("script", titleCase(part));
            } else if ((part.length() == 2 && part.chars().allMatch(Character::isLetter))
                    || (part.length() == 3 && part.chars().allMatch(Character::isDigit))) {
                out.put("region", PhpStrings.upper(part));
            } else if (part.length() >= 1) {
                out.put("variant" + variant++, PhpStrings.upper(part));
            }
        }
        return out;
    }

    private static String titleCase(String value) {
        String lower = PhpStrings.lower(value);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
