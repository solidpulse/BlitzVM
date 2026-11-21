<?php
var_dump(Locale::getPrimaryLanguage('en_US_POSIX'));
var_dump(Locale::getScript('zh_Hant_TW'), Locale::getRegion('zh_Hant_TW'));
var_dump(Locale::getPrimaryLanguage('sr_Latn_RS'));
var_dump(Locale::getAllVariants('de_DE_1996'));
var_dump(Locale::parseLocale('zh_Hant_TW'));
var_dump(Locale::composeLocale(['language' => 'zh', 'script' => 'Hant', 'region' => 'TW']));
var_dump(Locale::canonicalize('EN-us'));
var_dump(Locale::getPrimaryLanguage(''));

var_dump(Locale::setDefault('fr_FR'));
var_dump(Locale::getDefault());
var_dump(Locale::getPrimaryLanguage(Locale::getDefault()));

echo Locale::LANG_TAG, ' ', Locale::SCRIPT_TAG, ' ', Locale::REGION_TAG, "\n";
var_dump(Locale::DEFAULT_LOCALE);
