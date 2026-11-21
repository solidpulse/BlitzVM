<?php
$timezone = IntlTimeZone::createTimeZone('Europe/Brussels');
var_dump($timezone->getID(), $timezone->getRawOffset());

$unknown = IntlTimeZone::createTimeZone('Nonsense/Zone');
var_dump($unknown->getID(), $unknown->getRawOffset());
var_dump(IntlTimeZone::getUnknown()->getID());

$calendar = IntlCalendar::createInstance($timezone);
$calendar->setDateTime(2023, 10, 14, 22, 13, 20);

foreach ([
    IntlCalendar::FIELD_YEAR,
    IntlCalendar::FIELD_MONTH,
    IntlCalendar::FIELD_DAY_OF_MONTH,
    IntlCalendar::FIELD_HOUR_OF_DAY,
    IntlCalendar::FIELD_MINUTE,
    IntlCalendar::FIELD_SECOND,
    IntlCalendar::FIELD_DAY_OF_WEEK,
    IntlCalendar::FIELD_DAY_OF_YEAR,
] as $field) {
    echo $field, '=', $calendar->get($field), "\n";
}

echo $calendar->getType(), ' ', $calendar->getTimeZone()->getID(), ' ', $calendar->getLocale(Locale::VALID_LOCALE), "\n";
var_dump($calendar->getFirstDayOfWeek(), $calendar->getMinimalDaysInFirstWeek(), $calendar->isLenient());

$calendar->setDateTime(2023, 12, 40, 0, 0, 0);
echo $calendar->get(IntlCalendar::FIELD_YEAR), '-', $calendar->get(IntlCalendar::FIELD_MONTH) + 1, '-', $calendar->get(IntlCalendar::FIELD_DAY_OF_MONTH), "\n";

try {
    $calendar->get(99);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
