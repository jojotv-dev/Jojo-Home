-- PROPOSITION NON EXECUTEE. Accord distinct requis avant toute application.
-- jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Nouveau domaine de parametres/profils. Ne modifie aucune progression ni association pilote.
-- Apres accord, ajouter dans CETTE transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.account_schema_approved = 'APPROVED_SCHEMA_016';
begin;
set local lock_timeout='3s';
set local statement_timeout='30s';
set local row_security=on;
do $guard$
begin
    if current_user <> 'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.account_schema_approved',true) is distinct from 'APPROVED_SCHEMA_016'
       or not exists (select 1 from pg_roles where rolname=current_user and rolbypassrls and rolcreaterole) then
        raise exception 'ACCOUNT_SCHEMA_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if to_regnamespace('jojo_account_settings') is not null
       or exists (select 1 from pg_roles where rolname='jojo_account_settings_rpc')
       or to_regprocedure('jojo_progress_pilot.pilot_request_identity()') is null
       or to_regprocedure('jojo_progress_pilot.pilot_session_matches(uuid,uuid)') is null
       or not exists (select 1 from jojo_progress_pilot.pilot_devices
            where owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c' and id='f8648865-cd01-4be7-bd4a-2e2c7b6659ba' and active) then
        raise exception 'ACCOUNT_SCHEMA_PRECONDITION_FAILED';
    end if;
end;
$guard$;

create role jojo_account_settings_rpc nologin noinherit nobypassrls;
create schema jojo_account_settings authorization postgres;
revoke all on schema jojo_account_settings from public,anon,authenticated;
grant usage on schema jojo_account_settings,jojo_progress_pilot to jojo_account_settings_rpc;
grant execute on function jojo_progress_pilot.pilot_request_identity(),
    jojo_progress_pilot.pilot_session_matches(uuid,uuid) to jojo_account_settings_rpc;

create table jojo_account_settings.catalog (
    key text primary key,
    scope text not null check(scope in ('account','profile','device')),
    kind text not null check(kind in ('boolean','integer','enum','language','decimal','package','packages','enum_list','profile')),
    choices jsonb not null default '[]' check(jsonb_typeof(choices)='array'),
    minimum bigint not null default 0,
    maximum bigint not null default 0,
    unique(key,scope)
);
-- CATALOGUE_VALUES: generated from AccountSettingsCatalog, reviewed as ordinary SQL below.
insert into jojo_account_settings.catalog(key,scope,kind,choices,minimum,maximum) values
('home.icon_size','account','enum','["small","medium","large"]'::jsonb,0,0),
('home.home_rows','account','integer','[]'::jsonb,1,8),
('home.background_color','account','integer','[]'::jsonb,-2147483648,2147483647),
('home.background_theme_color','account','integer','[]'::jsonb,-1,64),
('home.button_theme','account','integer','[]'::jsonb,-1,64),
('home.folder_theme','account','integer','[]'::jsonb,-1,64),
('home.folder_icon_theme','account','integer','[]'::jsonb,-1,64),
('home.network_folder_theme','account','integer','[]'::jsonb,-1,64),
('home.home_category_background_theme','account','integer','[]'::jsonb,-1,64),
('home.player_color','account','integer','[]'::jsonb,-1,64),
('home.interface_style','account','enum','["Personnalis\u00e9","Bleu nuit","Ardoise","Turquoise","Bordeaux","Sable","Contraste"]'::jsonb,0,0),
('home.home_bars_style','account','enum','["transparent","black","frosted"]'::jsonb,0,0),
('home.home_hero_style','account','enum','["current","muted","airy","editorial"]'::jsonb,0,0),
('home.dynamic_wallpaper_mode','account','enum','["glow","gradient","solid"]'::jsonb,0,0),
('home.dynamic_wallpaper','account','boolean','[]'::jsonb,0,0),
('home.show_app_labels','account','boolean','[]'::jsonb,0,0),
('home.settings_grid','account','boolean','[]'::jsonb,0,0),
('home.files_grid','account','boolean','[]'::jsonb,0,0),
('home.show_hidden_files','account','boolean','[]'::jsonb,0,0),
('home.weather_widget','account','boolean','[]'::jsonb,0,0),
('home.app_labels_mode','account','enum','["focus","always","never"]'::jsonb,0,0),
('home.continue_watching_position','account','enum','["before","after"]'::jsonb,0,0),
('home.continue_watching_hero_size','account','enum','["small","medium","large"]'::jsonb,0,0),
('home.app_sort_mode','device','enum','["manual","alphabetical"]'::jsonb,0,0),
('home.start_at_boot','device','boolean','[]'::jsonb,0,0),
('home.autostart_after_standby','device','boolean','[]'::jsonb,0,0),
('home.fire_tv_home_redirect','device','boolean','[]'::jsonb,0,0),
('home.autostart_delay_seconds','device','integer','[]'::jsonb,3,15),
('home.apps_order','device','packages','[]'::jsonb,0,0),
('home.favorite_apps','device','packages','[]'::jsonb,0,0),
('home.hidden_apps','device','packages','[]'::jsonb,0,0),
('home.content_apps','device','packages','[]'::jsonb,0,0),
('home.player_forward_seconds','account','integer','[]'::jsonb,1,600),
('home.player_rewind_seconds','account','integer','[]'::jsonb,1,600),
('iptv.confirmExit','account','boolean','[]'::jsonb,0,0),
('iptv.epgDescriptions','account','boolean','[]'::jsonb,0,0),
('iptv.showLogos','account','boolean','[]'::jsonb,0,0),
('iptv.showFavoritesGroup','account','boolean','[]'::jsonb,0,0),
('iptv.showAllGroup','account','boolean','[]'::jsonb,0,0),
('iptv.currentProgramColor','account','boolean','[]'::jsonb,0,0),
('iptv.currentProgramProgressOnly','account','boolean','[]'::jsonb,0,0),
('iptv.currentTimeIndicator','account','boolean','[]'::jsonb,0,0),
('iptv.backToNow','account','boolean','[]'::jsonb,0,0),
('iptv.playerClock','account','boolean','[]'::jsonb,0,0),
('iptv.previewEnabled','account','boolean','[]'::jsonb,0,0),
('iptv.previewSound','account','boolean','[]'::jsonb,0,0),
('iptv.previewAutoPlay','account','boolean','[]'::jsonb,0,0),
('iptv.channelNumbers','account','boolean','[]'::jsonb,0,0),
('iptv.channelNames','account','boolean','[]'::jsonb,0,0),
('iptv.channelTwoLines','account','boolean','[]'::jsonb,0,0),
('iptv.programTwoLines','account','boolean','[]'::jsonb,0,0),
('iptv.currentChannelColor','account','boolean','[]'::jsonb,0,0),
('iptv.showAllPlaylistsGroup','account','boolean','[]'::jsonb,0,0),
('iptv.favoritesFirst','account','boolean','[]'::jsonb,0,0),
('iptv.groupChannelsByPlaylist','account','boolean','[]'::jsonb,0,0),
('iptv.searchFavoritesFirst','account','boolean','[]'::jsonb,0,0),
('iptv.searchPastWithoutReplay','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoDate','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoTime','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoMedia','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoResolution','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoDescription','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoDescriptionOnChange','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoGroup','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoBottom','account','boolean','[]'::jsonb,0,0),
('iptv.playerInfoCard','account','boolean','[]'::jsonb,0,0),
('iptv.skipIntroEnabled','account','boolean','[]'::jsonb,0,0),
('iptv.channelNameEditor','account','boolean','[]'::jsonb,0,0),
('iptv.showCatchupIcon','account','boolean','[]'::jsonb,0,0),
('iptv.animatedChannelScroll','account','boolean','[]'::jsonb,0,0),
('iptv.playerListCurrentColor','account','boolean','[]'::jsonb,0,0),
('iptv.playerListDimPast','account','boolean','[]'::jsonb,0,0),
('iptv.playerListOverlayPrograms','account','boolean','[]'::jsonb,0,0),
('iptv.playerListOverlayDescription','account','boolean','[]'::jsonb,0,0),
('iptv.playerHistoryGuide','account','boolean','[]'::jsonb,0,0),
('iptv.playerHistoryButton','account','boolean','[]'::jsonb,0,0),
('iptv.playerHistoryPrograms','account','boolean','[]'::jsonb,0,0),
('iptv.playerHistoryPast','account','boolean','[]'::jsonb,0,0),
('iptv.playerRecentNames','account','boolean','[]'::jsonb,0,0),
('iptv.textSize','device','enum','["small","medium","large","extraLarge"]'::jsonb,0,0),
('iptv.themeBackground','account','enum','["home","0","1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37","38","39","40","41","42","43","44","45","46","47","48","49","50","51","52","53","54","55","56","57","58","59","60","61","62","63"]'::jsonb,0,0),
('iptv.themeAccent','account','enum','["home","0","1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37","38","39","40","41","42","43","44","45","46","47","48","49","50","51","52","53","54","55","56","57","58","59","60","61","62","63"]'::jsonb,0,0),
('iptv.interfaceTransparency','device','integer','[]'::jsonb,0,100),
('iptv.epgPastDays','account','enum','["1","3","7","14"]'::jsonb,0,0),
('iptv.historyDays','account','enum','["1","2","3","5","7","14","30"]'::jsonb,0,0),
('iptv.historyDelay','account','enum','["0","5","10","15","30","60","120"]'::jsonb,0,0),
('iptv.groupSort','account','enum','["playlist","name"]'::jsonb,0,0),
('iptv.channelSort','account','enum','["playlist","name","nameDescending","added","watchTime","manual"]'::jsonb,0,0),
('iptv.sourceSort','account','enum','["playlist","name","manual"]'::jsonb,0,0),
('iptv.playerPanelTimeout','account','enum','["0","2","3","5","10","15","30","60"]'::jsonb,0,0),
('iptv.playerRecentCount','account','enum','["5","10","15","20","30","50"]'::jsonb,0,0),
('iptv.playerRecentDelay','account','enum','["0","5","10","15","30","60","120"]'::jsonb,0,0),
('iptv.playerClockPosition','device','enum','["topRight","topLeft","bottomRight","bottomLeft"]'::jsonb,0,0),
('iptv.playerClockSize','device','enum','["small","medium","large"]'::jsonb,0,0),
('iptv.playerClockTransparency','device','enum','["none","low","medium","high"]'::jsonb,0,0),
('iptv.recordingBefore','account','enum','["0","1","2","3","4","5","10","15","20","30","60"]'::jsonb,0,0),
('iptv.recordingAfter','account','enum','["0","1","2","3","4","5","10","15","20","30","60"]'::jsonb,0,0),
('iptv.searchHistory','account','boolean','[]'::jsonb,0,0),
('iptv.searchStay','device','boolean','[]'::jsonb,0,0),
('iptv.searchVoice','device','boolean','[]'::jsonb,0,0),
('iptv.rememberLastProfile','device','boolean','[]'::jsonb,0,0),
('iptv.reminderBefore','account','enum','["0","1","3","5","10","15","30","60"]'::jsonb,0,0),
('iptv.reminderTimeout','device','enum','["5","10","15","30","60","120"]'::jsonb,0,0),
('iptv.reminderAction','device','enum','["watch","details"]'::jsonb,0,0),
('iptv.previewFullscreenDelay','device','enum','["0","10","30","60","120","180","300"]'::jsonb,0,0),
('iptv.channelClick','device','enum','["preview","fullscreen"]'::jsonb,0,0),
('iptv.epgRefreshHours','device','enum','["0","1","2","4","6","8","12","16","24","48","72","96","120","144","168"]'::jsonb,0,0),
('iptv.afr','device','enum','["OFF","START","START_STOP"]'::jsonb,0,0),
('iptv.decoderPriority','device','enum','["default","hardware","software"]'::jsonb,0,0),
('iptv.externalPlayerMode','device','enum','["all","never","vod"]'::jsonb,0,0),
('iptv.externalPlayerPackage','device','package','[]'::jsonb,0,0),
('iptv.playerMenu:search','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:channels','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:record','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:multiview','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:video','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:audio','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:audioDelay','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:subtitles','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:display','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:sleep','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:favorite','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:settings','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:history','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:movies','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:series','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:myList','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenu:exit','device','boolean','[]'::jsonb,0,0),
('iptv.playerMenuOrder','device','enum_list','["search","channels","record","multiview","video","audio","audioDelay","subtitles","display","sleep","favorite","settings","history","movies","series","myList","exit"]'::jsonb,0,0),
('iptv.seekSeconds','device','enum','["5","10","15","30","60"]'::jsonb,0,0),
('iptv.playLastChannel','device','boolean','[]'::jsonb,0,0),
('iptv.afrLive','device','boolean','[]'::jsonb,0,0),
('iptv.afrVod','device','boolean','[]'::jsonb,0,0),
('iptv.afrRefresh','device','boolean','[]'::jsonb,0,0),
('iptv.afrResolution','device','boolean','[]'::jsonb,0,0),
('iptv.audioPassthrough','device','boolean','[]'::jsonb,0,0),
('iptv.preferSurround','device','boolean','[]'::jsonb,0,0),
('iptv.playerBlackScreen','device','boolean','[]'::jsonb,0,0),
('iptv.reminderWake','device','boolean','[]'::jsonb,0,0),
('iptv.epgUpdateOnStart','device','boolean','[]'::jsonb,0,0),
('iptv.epgUpdateOnPlaylist','device','boolean','[]'::jsonb,0,0),
('iptv.previewStayGuide','device','boolean','[]'::jsonb,0,0),
('iptv.previewTransition','device','boolean','[]'::jsonb,0,0),
('iptv.playerListOverlayAutoPlay','device','boolean','[]'::jsonb,0,0),
('iptv.playerListPreviewAutoPlay','device','boolean','[]'::jsonb,0,0),
('iptv.playerListPreviewStay','device','boolean','[]'::jsonb,0,0),
('iptv.playerListOverlayStay','device','boolean','[]'::jsonb,0,0),
('iptv.guideOverlayStay','device','boolean','[]'::jsonb,0,0),
('iptv.playerListPreviewTransition','device','boolean','[]'::jsonb,0,0),
('iptv.bufferSize','device','enum','["none","small","medium","large","extraLarge"]'::jsonb,0,0),
('iptv.afrDelay','device','enum','["0","1","2","3","4","5","6","7","8","9","10"]'::jsonb,0,0),
('iptv.audioLanguage','profile','language','[]'::jsonb,0,0),
('iptv.secondaryAudioLanguage','profile','language','[]'::jsonb,0,0),
('iptv.subtitleLanguage','profile','language','[]'::jsonb,0,0),
('iptv.secondarySubtitleLanguage','profile','language','[]'::jsonb,0,0),
('iptv.subtitleUseForced','profile','boolean','[]'::jsonb,0,0),
('iptv.subtitleShowOnlyPreferredLanguages','profile','boolean','[]'::jsonb,0,0),
('iptv.autoNextEpisode','profile','boolean','[]'::jsonb,0,0),
('iptv.nextEpisodeThresholdMode','profile','enum','["PERCENTAGE","MINUTES_BEFORE_END"]'::jsonb,0,0),
('iptv.nextEpisodeThresholdPercent','profile','decimal','[]'::jsonb,95,100),
('iptv.nextEpisodeThresholdMinutesBeforeEnd','profile','decimal','[]'::jsonb,0,30),
('iptv.remote.guide.ok','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.okLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.back','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.backLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.left','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.leftLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.right','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.rightLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.up','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.upLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.down','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.downLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.pageUp','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.pageDown','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.volumeUp','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.volumeDown','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.mute','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.menu','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.menuLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.play','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.playLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.stop','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.rewind','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.forward','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.record','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.delete','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.info','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.guide','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.red','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.green','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.yellow','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.guide.blue','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.ok','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.okLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.back','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.backLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.left','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.leftLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.right','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.rightLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.up','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.upLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.down','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.downLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.pageUp','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.pageDown','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.volumeUp','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.volumeDown','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.mute','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.menu','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.menuLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.play','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.playLong','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.stop','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.rewind','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.forward','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.record','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.delete','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.info','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.guide','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.red','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.green','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.yellow','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('iptv.remote.player.blue','device','enum','["IGNORE","WATCH","BEGINNING","EXTERNAL","MENU","GROUPS","DESCRIPTION","SEARCH","SETTINGS","CHANNEL_OPTIONS","GROUP_OPTIONS","MOVIES","SERIES","RECORDINGS","CUSTOM_RECORDING","MY_LIST","FAVORITE","MANAGE_FAVORITES","BLOCKING","VISIBILITY","REORDER","COPY","RETURN_PLAYER","RETURN_NOW","BACK","EXIT","PAGE_UP","PAGE_DOWN","NEXT_PROGRAMS","CHANNELS_OVERLAY","CHANNELS_PREVIEW","GROUPS_OVERLAY","GROUPS_PREVIEW","GUIDE_OVERLAY","GUIDE_PREVIEW","INFO_CONTROLS","INFO","CONTROLS","RECENT","NEXT","PREVIOUS","INFO_NEXT","INFO_PREVIOUS","HISTORY","PLAY_PAUSE","STOP","LIVE","RECORD","VOLUME_UP","VOLUME_DOWN","MUTE","REWIND","FORWARD","AUDIO","SUBTITLES","ASPECT"]'::jsonb,0,0),
('profiles.home','profile','profile','["","animal:aigle","animal:baleine","animal:cerf","animal:chat","animal:cheval","animal:chevre","animal:chien","animal:dauphin","animal:ecureuil","animal:elephant","animal:girafe","animal:herisson","animal:hippopotame","animal:kangourou","animal:koala","animal:lapin","animal:lion","animal:loup","animal:mouton","animal:ours","animal:panda","animal:perroquet","animal:renard","animal:requin","animal:rhinoceros","animal:sanglier","animal:tigre","animal:tortue","animal:vache","animal:zebre","family:femme_cartoon_01","family:femme_cartoon_02","family:femme_cartoon_03","family:femme_cartoon_04","family:femme_cartoon_05","family:femme_cartoon_06","family:femme_cartoon_07","family:femme_cartoon_08","family:femme_cartoon_09","family:femme_cartoon_10","family:femme_cartoon_2_01","family:femme_cartoon_2_02","family:femme_cartoon_2_03","family:femme_cartoon_2_04","family:femme_cartoon_2_05","family:femme_cartoon_2_06","family:femme_cartoon_2_07","family:femme_cartoon_2_08","family:femme_cartoon_2_09","family:femme_cartoon_2_10","family:femme_photo_1_01","family:femme_photo_1_02","family:femme_photo_1_03","family:femme_photo_1_04","family:femme_photo_1_05","family:femme_photo_1_06","family:femme_photo_1_07","family:femme_photo_1_08","family:femme_photo_1_09","family:femme_photo_1_10","family:femme_photo_2_01","family:femme_photo_2_02","family:femme_photo_2_03","family:femme_photo_2_04","family:femme_photo_2_05","family:femme_photo_2_06","family:femme_photo_2_07","family:femme_photo_2_08","family:femme_photo_2_09","family:femme_photo_2_10","family:homme_00","family:homme_01","family:homme_02","family:homme_03","family:homme_04","family:homme_05","family:homme_06","family:homme_07","family:homme_08","family:homme_09","family:homme_10","family:homme_11","family:homme_12","family:homme_13","family:homme_14","family:homme_15","family:homme_16","family:homme_17","family:homme_18","family:homme_19","family:homme_20","family:homme_21","emoji:\ud83d\ude00","emoji:\ud83d\ude03","emoji:\ud83d\ude04","emoji:\ud83d\ude01","emoji:\ud83d\ude06","emoji:\ud83d\ude05","emoji:\ud83d\ude02","emoji:\ud83d\ude42","emoji:\ud83d\ude43","emoji:\ud83d\ude09","emoji:\ud83d\ude0a","emoji:\ud83d\ude0d","emoji:\ud83e\udd70","emoji:\ud83d\ude18","emoji:\ud83d\ude0e","emoji:\ud83e\udd13","emoji:\ud83e\udd73","emoji:\ud83e\udd29","emoji:\ud83d\ude07","emoji:\ud83e\udd20","emoji:\ud83e\udd78","emoji:\ud83e\udd17","emoji:\ud83d\ude34","emoji:\ud83e\udd14","emoji:\ud83d\udc68","emoji:\ud83d\udc69","emoji:\ud83d\udc66","emoji:\ud83d\udc67","emoji:\ud83d\udc68\ud83c\udffb","emoji:\ud83d\udc69\ud83c\udffb","emoji:\ud83d\udc66\ud83c\udffb","emoji:\ud83d\udc67\ud83c\udffb","emoji:\ud83d\udc68\ud83c\udffc","emoji:\ud83d\udc69\ud83c\udffc","emoji:\ud83d\udc66\ud83c\udffc","emoji:\ud83d\udc67\ud83c\udffc","emoji:\ud83d\udc68\ud83c\udffd","emoji:\ud83d\udc69\ud83c\udffd","emoji:\ud83d\udc66\ud83c\udffd","emoji:\ud83d\udc67\ud83c\udffd","emoji:\ud83d\udc68\ud83c\udffe","emoji:\ud83d\udc69\ud83c\udffe","emoji:\ud83d\udc66\ud83c\udffe","emoji:\ud83d\udc67\ud83c\udffe","emoji:\ud83d\udc68\ud83c\udfff","emoji:\ud83d\udc69\ud83c\udfff","emoji:\ud83d\udc66\ud83c\udfff","emoji:\ud83d\udc67\ud83c\udfff","emoji:\ud83e\udd21","emoji:\ud83d\udc7b","emoji:\ud83d\udc7d","emoji:\ud83e\udd16","emoji:\ud83d\udca9","emoji:\ud83d\udc79","emoji:\ud83d\udc7a","emoji:\ud83d\ude08","emoji:\ud83d\ude3a","emoji:\ud83d\ude38","emoji:\ud83d\ude39","emoji:\ud83d\ude3b","emoji:\ud83d\ude40","emoji:\ud83d\ude3c","emoji:\ud83d\ude48","emoji:\ud83d\ude49","emoji:\ud83d\ude4a","emoji:\ud83d\udc35","emoji:\ud83d\udc36","emoji:\ud83d\udc31","emoji:\ud83e\udd8a","emoji:\ud83d\udc3c","emoji:\ud83e\udd81","emoji:\ud83d\udc38","fallback_blue","fallback_violet","fallback_pink","fallback_red","fallback_orange","fallback_yellow","fallback_green","fallback_teal","fallback_cyan","fallback_indigo","fallback_brown","fallback_slate"]'::jsonb,0,0),
('profiles.iptv','profile','profile','["","animal:aigle","animal:baleine","animal:cerf","animal:chat","animal:cheval","animal:chevre","animal:chien","animal:dauphin","animal:ecureuil","animal:elephant","animal:girafe","animal:herisson","animal:hippopotame","animal:kangourou","animal:koala","animal:lapin","animal:lion","animal:loup","animal:mouton","animal:ours","animal:panda","animal:perroquet","animal:renard","animal:requin","animal:rhinoceros","animal:sanglier","animal:tigre","animal:tortue","animal:vache","animal:zebre","family:femme_cartoon_01","family:femme_cartoon_02","family:femme_cartoon_03","family:femme_cartoon_04","family:femme_cartoon_05","family:femme_cartoon_06","family:femme_cartoon_07","family:femme_cartoon_08","family:femme_cartoon_09","family:femme_cartoon_10","family:femme_cartoon_2_01","family:femme_cartoon_2_02","family:femme_cartoon_2_03","family:femme_cartoon_2_04","family:femme_cartoon_2_05","family:femme_cartoon_2_06","family:femme_cartoon_2_07","family:femme_cartoon_2_08","family:femme_cartoon_2_09","family:femme_cartoon_2_10","family:femme_photo_1_01","family:femme_photo_1_02","family:femme_photo_1_03","family:femme_photo_1_04","family:femme_photo_1_05","family:femme_photo_1_06","family:femme_photo_1_07","family:femme_photo_1_08","family:femme_photo_1_09","family:femme_photo_1_10","family:femme_photo_2_01","family:femme_photo_2_02","family:femme_photo_2_03","family:femme_photo_2_04","family:femme_photo_2_05","family:femme_photo_2_06","family:femme_photo_2_07","family:femme_photo_2_08","family:femme_photo_2_09","family:femme_photo_2_10","family:homme_00","family:homme_01","family:homme_02","family:homme_03","family:homme_04","family:homme_05","family:homme_06","family:homme_07","family:homme_08","family:homme_09","family:homme_10","family:homme_11","family:homme_12","family:homme_13","family:homme_14","family:homme_15","family:homme_16","family:homme_17","family:homme_18","family:homme_19","family:homme_20","family:homme_21","emoji:\ud83d\ude00","emoji:\ud83d\ude03","emoji:\ud83d\ude04","emoji:\ud83d\ude01","emoji:\ud83d\ude06","emoji:\ud83d\ude05","emoji:\ud83d\ude02","emoji:\ud83d\ude42","emoji:\ud83d\ude43","emoji:\ud83d\ude09","emoji:\ud83d\ude0a","emoji:\ud83d\ude0d","emoji:\ud83e\udd70","emoji:\ud83d\ude18","emoji:\ud83d\ude0e","emoji:\ud83e\udd13","emoji:\ud83e\udd73","emoji:\ud83e\udd29","emoji:\ud83d\ude07","emoji:\ud83e\udd20","emoji:\ud83e\udd78","emoji:\ud83e\udd17","emoji:\ud83d\ude34","emoji:\ud83e\udd14","emoji:\ud83d\udc68","emoji:\ud83d\udc69","emoji:\ud83d\udc66","emoji:\ud83d\udc67","emoji:\ud83d\udc68\ud83c\udffb","emoji:\ud83d\udc69\ud83c\udffb","emoji:\ud83d\udc66\ud83c\udffb","emoji:\ud83d\udc67\ud83c\udffb","emoji:\ud83d\udc68\ud83c\udffc","emoji:\ud83d\udc69\ud83c\udffc","emoji:\ud83d\udc66\ud83c\udffc","emoji:\ud83d\udc67\ud83c\udffc","emoji:\ud83d\udc68\ud83c\udffd","emoji:\ud83d\udc69\ud83c\udffd","emoji:\ud83d\udc66\ud83c\udffd","emoji:\ud83d\udc67\ud83c\udffd","emoji:\ud83d\udc68\ud83c\udffe","emoji:\ud83d\udc69\ud83c\udffe","emoji:\ud83d\udc66\ud83c\udffe","emoji:\ud83d\udc67\ud83c\udffe","emoji:\ud83d\udc68\ud83c\udfff","emoji:\ud83d\udc69\ud83c\udfff","emoji:\ud83d\udc66\ud83c\udfff","emoji:\ud83d\udc67\ud83c\udfff","emoji:\ud83e\udd21","emoji:\ud83d\udc7b","emoji:\ud83d\udc7d","emoji:\ud83e\udd16","emoji:\ud83d\udca9","emoji:\ud83d\udc79","emoji:\ud83d\udc7a","emoji:\ud83d\ude08","emoji:\ud83d\ude3a","emoji:\ud83d\ude38","emoji:\ud83d\ude39","emoji:\ud83d\ude3b","emoji:\ud83d\ude40","emoji:\ud83d\ude3c","emoji:\ud83d\ude48","emoji:\ud83d\ude49","emoji:\ud83d\ude4a","emoji:\ud83d\udc35","emoji:\ud83d\udc36","emoji:\ud83d\udc31","emoji:\ud83e\udd8a","emoji:\ud83d\udc3c","emoji:\ud83e\udd81","emoji:\ud83d\udc38","fallback_blue","fallback_violet","fallback_pink","fallback_red","fallback_orange","fallback_yellow","fallback_green","fallback_teal","fallback_cyan","fallback_indigo","fallback_brown","fallback_slate"]'::jsonb,0,0);

create table jojo_account_settings.accounts (
    owner_id uuid primary key references auth.users(id) on delete cascade,
    seed_legacy_device uuid not null,
    seed_auth_session uuid not null,
    schema_version integer not null default 1 check(schema_version=1),
    initialized boolean not null default false,
    seed_operation uuid,
    seed_hash text check(seed_hash ~ '^[0-9a-f]{64}$'),
    head_revision bigint not null default 0 check(head_revision>=0),
    receipt_count integer not null default 0 check(receipt_count between 0 and 100000),
    check(initialized=(seed_operation is not null and seed_hash is not null))
);
-- Configuration d'initialisation seulement : aucune preference ni profil n'est importe ici.
insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session)
select owner_id,id,auth_session_id from jojo_progress_pilot.pilot_devices
where owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c' and id='f8648865-cd01-4be7-bd4a-2e2c7b6659ba' and active;

create table jojo_account_settings.installations (
    owner_id uuid not null references jojo_account_settings.accounts on delete cascade,
    id uuid not null check(id<>'00000000-0000-0000-0000-000000000000'),
    proof_hash text not null check(proof_hash ~ '^[0-9a-f]{64}$'),
    auth_session_id uuid not null,
    platform text not null check(platform in ('firetv','androidtv')),
    active boolean not null default true,
    primary key(owner_id,id),
    unique(owner_id,auth_session_id)
);
create table jojo_account_settings.profiles (
    owner_id uuid not null references jojo_account_settings.accounts on delete cascade,
    id uuid not null check(id<>'00000000-0000-0000-0000-000000000000'),
    domain text not null check(domain in ('home','iptv')),
    primary_profile boolean not null,
    primary key(owner_id,id),
    check(domain='home' or not primary_profile)
);
create unique index account_one_primary_profile on jojo_account_settings.profiles(owner_id)
where domain='home' and primary_profile;
create table jojo_account_settings.entries (
    owner_id uuid not null references jojo_account_settings.accounts on delete cascade,
    scope text not null,
    subject uuid not null,
    key text not null,
    value jsonb not null check(octet_length(value::text)<=65536),
    revision bigint not null check(revision>0),
    last_device uuid not null,
    received_at timestamptz not null default clock_timestamp(),
    primary key(owner_id,scope,subject,key),
    unique(owner_id,revision),
    foreign key(key,scope) references jojo_account_settings.catalog(key,scope),
    foreign key(owner_id,last_device) references jojo_account_settings.installations(owner_id,id),
    check((scope='account')=(subject='00000000-0000-0000-0000-000000000000'))
);
create index account_entries_read on jojo_account_settings.entries(owner_id,scope,subject,revision);
create table jojo_account_settings.receipts (
    owner_id uuid not null,
    device_id uuid not null,
    operation_id uuid not null,
    body_hash text not null check(body_hash ~ '^[0-9a-f]{64}$'),
    status text not null check(status in ('applied','conflict')),
    server_revision bigint not null check(server_revision>=0),
    primary key(owner_id,device_id,operation_id),
    foreign key(owner_id,device_id) references jojo_account_settings.installations(owner_id,id) on delete cascade,
    check(status<>'applied' or server_revision>0)
);

-- Only the already reviewed boolean Auth helper may read auth.sessions.
create function jojo_account_settings.request_owner() returns uuid
language sql stable security invoker set search_path='' begin atomic
    select case when jojo_progress_pilot.pilot_session_matches(i.owner_id,i.session_id) then i.owner_id end
    from jojo_progress_pilot.pilot_request_identity() i;
end;
create function jojo_account_settings.seed_session_allowed() returns boolean
language sql stable security invoker set search_path='' begin atomic
    select coalesce(exists(select 1 from jojo_account_settings.accounts a
        cross join jojo_progress_pilot.pilot_request_identity() i
        where a.owner_id=i.owner_id and a.seed_auth_session=i.session_id
            and jojo_progress_pilot.pilot_session_matches(i.owner_id,i.session_id)),false);
end;

alter table jojo_account_settings.catalog enable row level security;
alter table jojo_account_settings.catalog force row level security;
alter table jojo_account_settings.accounts enable row level security;
alter table jojo_account_settings.accounts force row level security;
alter table jojo_account_settings.installations enable row level security;
alter table jojo_account_settings.installations force row level security;
alter table jojo_account_settings.profiles enable row level security;
alter table jojo_account_settings.profiles force row level security;
alter table jojo_account_settings.entries enable row level security;
alter table jojo_account_settings.entries force row level security;
alter table jojo_account_settings.receipts enable row level security;
alter table jojo_account_settings.receipts force row level security;
create policy catalog_select on jojo_account_settings.catalog for select to jojo_account_settings_rpc using(true);
create policy accounts_select on jojo_account_settings.accounts for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy accounts_update on jojo_account_settings.accounts for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy installation_select on jojo_account_settings.installations for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy installation_insert on jojo_account_settings.installations for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and active
        and auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity()));
create policy installation_update on jojo_account_settings.installations for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and active)
    with check(owner_id=jojo_account_settings.request_owner() and active
        and auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity()));
create policy profile_select on jojo_account_settings.profiles for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy profile_insert on jojo_account_settings.profiles for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy entries_select on jojo_account_settings.entries for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and (scope<>'profile' or exists(
        select 1 from jojo_account_settings.profiles p where p.owner_id=entries.owner_id and p.id=entries.subject
            and p.domain=case when entries.key='profiles.home' then 'home' else 'iptv' end)) and (scope<>'device' or exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=entries.owner_id and d.id=entries.subject
            and d.active and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity()))));
create policy entries_insert on jojo_account_settings.entries for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and (scope<>'profile' or exists(
        select 1 from jojo_account_settings.profiles p where p.owner_id=entries.owner_id and p.id=entries.subject
            and p.domain=case when entries.key='profiles.home' then 'home' else 'iptv' end)) and exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=entries.owner_id and d.id=entries.last_device
            and d.active and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())
            and (entries.scope<>'device' or entries.subject=d.id)));
create policy entries_update on jojo_account_settings.entries for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and (scope<>'profile' or exists(
        select 1 from jojo_account_settings.profiles p where p.owner_id=entries.owner_id and p.id=entries.subject
            and p.domain=case when entries.key='profiles.home' then 'home' else 'iptv' end)) and (scope<>'device' or exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=entries.owner_id and d.id=entries.subject
            and d.active and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity()))))
    with check(owner_id=jojo_account_settings.request_owner() and (scope<>'profile' or exists(
        select 1 from jojo_account_settings.profiles p where p.owner_id=entries.owner_id and p.id=entries.subject
            and p.domain=case when entries.key='profiles.home' then 'home' else 'iptv' end)) and exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=entries.owner_id and d.id=entries.last_device
            and d.active and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())
            and (entries.scope<>'device' or entries.subject=d.id)));
create policy receipt_select on jojo_account_settings.receipts for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=receipts.owner_id and d.id=receipts.device_id
            and d.active and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())));
create policy receipt_insert on jojo_account_settings.receipts for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=receipts.owner_id and d.id=receipts.device_id
            and d.active and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())));

revoke all on all tables in schema jojo_account_settings from public,anon,authenticated,jojo_account_settings_rpc;
grant select on all tables in schema jojo_account_settings to jojo_account_settings_rpc;
grant insert on jojo_account_settings.installations,jojo_account_settings.profiles,jojo_account_settings.entries,jojo_account_settings.receipts to jojo_account_settings_rpc;
grant update(auth_session_id,platform) on jojo_account_settings.installations to jojo_account_settings_rpc;
grant update(initialized,seed_operation,seed_hash,head_revision,receipt_count) on jojo_account_settings.accounts to jojo_account_settings_rpc;
grant update(value,revision,last_device,received_at) on jojo_account_settings.entries to jojo_account_settings_rpc;

create function jojo_account_settings.require_device(p_device uuid,p_proof text) returns uuid
language plpgsql security invoker set search_path='' as $$
declare v_owner uuid:=jojo_account_settings.request_owner();
begin
    if v_owner is null or p_proof is null or p_proof !~ '^[0-9a-f]{64}$' or not exists(
        select 1 from jojo_account_settings.installations d where d.owner_id=v_owner and d.id=p_device and d.active
            and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())
            and d.proof_hash=encode(sha256(convert_to(p_proof,'UTF8')),'hex')) then
        raise exception using errcode='42501',message='ACCOUNT_DEVICE_NOT_AUTHORIZED';
    end if;
    return v_owner;
end;
$$;

create function public.account_settings_register(p_device uuid,p_proof text,p_platform text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid:=jojo_account_settings.request_owner(); v_session uuid;
    existing jojo_account_settings.installations%rowtype;
begin
    if v_owner is null or p_device is null or p_device='00000000-0000-0000-0000-000000000000'
       or p_proof is null or p_proof !~ '^[0-9a-f]{64}$' or p_platform is null or p_platform not in ('firetv','androidtv') then
        raise exception using errcode='42501',message='ACCOUNT_REGISTRATION_INVALID';
    end if;
    perform 1 from jojo_account_settings.accounts where owner_id=v_owner for update;
    if not found then raise exception using errcode='42501',message='ACCOUNT_NOT_ENABLED'; end if;
    select session_id into strict v_session from jojo_progress_pilot.pilot_request_identity();
    select * into existing from jojo_account_settings.installations where owner_id=v_owner and id=p_device;
    if found then
        if not existing.active or existing.proof_hash<>encode(sha256(convert_to(p_proof,'UTF8')),'hex') then
            raise exception using errcode='42501',message='ACCOUNT_DEVICE_PROOF_MISMATCH';
        end if;
        update jojo_account_settings.installations set auth_session_id=v_session,platform=p_platform
            where owner_id=v_owner and id=p_device and (auth_session_id<>v_session or platform<>p_platform);
    else
        if (select count(*) from jojo_account_settings.installations where owner_id=v_owner)>=32 then
            raise exception using errcode='54000',message='ACCOUNT_DEVICE_LIMIT';
        end if;
        insert into jojo_account_settings.installations(owner_id,id,proof_hash,auth_session_id,platform)
        values(v_owner,p_device,encode(sha256(convert_to(p_proof,'UTF8')),'hex'),v_session,p_platform);
    end if;
    return jsonb_build_object('schema',1,'device',p_device);
end;
$$;

create function jojo_account_settings.validate_value(p_key text,p_scope text,p_value jsonb) returns void
language plpgsql security invoker set search_path='' as $$
declare spec jojo_account_settings.catalog%rowtype; value_text text; valid boolean:=false;
begin
    select * into spec from jojo_account_settings.catalog where key=p_key and scope=p_scope;
    if not found or p_value is null or octet_length(p_value::text)>65536 then
        raise exception using errcode='22023',message='ACCOUNT_SETTING_NOT_ALLOWED';
    end if;
    value_text:=p_value#>>'{}';
    if p_value='null'::jsonb then valid:=spec.kind<>'profile';
    elsif spec.kind='boolean' then valid:=jsonb_typeof(p_value)='boolean';
    elsif spec.kind='integer' and jsonb_typeof(p_value)='number' and value_text ~ '^-?[0-9]+$' then
        valid:=value_text::numeric between spec.minimum and spec.maximum;
    elsif spec.kind='enum' then valid:=jsonb_typeof(p_value)='string' and spec.choices ? value_text;
    elsif spec.kind='language' then valid:=jsonb_typeof(p_value)='string' and value_text ~ '^(?:[a-z]{2,3}(?:-[A-Za-z]{2,4})?|device|none|system|original|auto|off)$';
    elsif spec.kind='decimal' and jsonb_typeof(p_value)='string' and value_text ~ '^[0-9]+(?:\.[0-9]+)?$' then
        valid:=value_text::numeric between spec.minimum and spec.maximum;
    elsif spec.kind='package' then
        valid:=jsonb_typeof(p_value)='string' and (value_text='' or length(value_text)<=200 and value_text ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$');
    elsif spec.kind='enum_list' and jsonb_typeof(p_value)='array' then
        valid:=jsonb_array_length(p_value)<=jsonb_array_length(spec.choices) and not exists(select 1 from jsonb_array_elements(p_value) x
            where jsonb_typeof(x)<>'string' or not (spec.choices ? (x#>>'{}')))
            and (select count(*)=count(distinct x) from jsonb_array_elements(p_value) x);
    elsif spec.kind='packages' and jsonb_typeof(p_value)='array' then
        valid:=jsonb_array_length(p_value)<=200 and not exists(select 1 from jsonb_array_elements(p_value) x
            where jsonb_typeof(x)<>'string' or length(x#>>'{}')>200 or (x#>>'{}') !~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
            and (select count(*)=count(distinct x) from jsonb_array_elements(p_value) x);
    elsif spec.kind='profile' and jsonb_typeof(p_value)='object' then
        valid:=(select count(*)=4 from jsonb_object_keys(p_value)) and p_value ?& array['name','color','avatar','primary']
            and jsonb_typeof(p_value->'name')='string' and length(p_value->>'name') between 1 and case when p_key='profiles.iptv' then 40 else 64 end
            and btrim(p_value->>'name')<>'' and (p_value->>'name') !~ '[[:cntrl:]]'
            and jsonb_typeof(p_value->'color')='string' and (p_value->>'color') ~ '^#[0-9a-fA-F]{6}$'
            and jsonb_typeof(p_value->'avatar')='string' and spec.choices ? (p_value->>'avatar')
            and jsonb_typeof(p_value->'primary')='boolean';
    end if;
    if valid is distinct from true then raise exception using errcode='22023',message='ACCOUNT_VALUE_INVALID'; end if;
end;
$$;

create function jojo_account_settings.apply_operations(p_device uuid,p_ops jsonb,p_limit integer) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare v_owner uuid:=jojo_account_settings.request_owner(); item jsonb; op uuid; expected bigint;
    v_scope text; v_subject uuid; v_key text; v_value jsonb; hash text; previous jojo_account_settings.receipts%rowtype;
    current_revision bigint; next_revision bigint; status text; result jsonb:='[]'; v_domain text; existing jojo_account_settings.profiles%rowtype;
begin
    if jsonb_typeof(p_ops) is distinct from 'array' or jsonb_array_length(p_ops) not between 1 and p_limit
       or octet_length(p_ops::text)>1048576 then raise exception using errcode='22023',message='ACCOUNT_BATCH_INVALID'; end if;
    if (select count(*)<>count(distinct x->>'operation_id') from jsonb_array_elements(p_ops) x) then
        raise exception using errcode='22023',message='ACCOUNT_DUPLICATE_OPERATION';
    end if;
    for item in select x from jsonb_array_elements(p_ops) x loop
        if jsonb_typeof(item)<>'object' or (select count(*) from jsonb_object_keys(item))<>6
           or not item ?& array['operation_id','expected_revision','scope','subject','key','value']
           or jsonb_typeof(item->'operation_id')<>'string' or (item->>'operation_id') !~ '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
           or jsonb_typeof(item->'subject')<>'string' or (item->>'subject') !~ '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
           or jsonb_typeof(item->'scope')<>'string' or jsonb_typeof(item->'key')<>'string'
           or jsonb_typeof(item->'expected_revision')<>'number' or (item->>'expected_revision') !~ '^[0-9]+$'
           or (item->>'expected_revision')::numeric>9223372036854775807 then
            raise exception using errcode='22023',message='ACCOUNT_OPERATION_INVALID';
        end if;
        op:=(item->>'operation_id')::uuid; expected:=(item->>'expected_revision')::bigint;
        v_scope:=item->>'scope'; v_subject:=(item->>'subject')::uuid; v_key:=item->>'key'; v_value:=item->'value';
        perform jojo_account_settings.validate_value(v_key,v_scope,v_value);
        if (v_scope='account') is distinct from (v_subject='00000000-0000-0000-0000-000000000000')
           or (v_scope='device' and v_subject<>p_device) then
            raise exception using errcode='42501',message='ACCOUNT_SCOPE_FORBIDDEN';
        end if;
        hash:=encode(sha256(convert_to(item::text,'UTF8')),'hex');
        select * into previous from jojo_account_settings.receipts where owner_id=v_owner and device_id=p_device and operation_id=op;
        if found then
            if previous.body_hash<>hash then raise exception using errcode='22023',message='ACCOUNT_REPLAY_BODY_CHANGED'; end if;
            result:=result||jsonb_build_array(jsonb_build_object('operation_id',op,'status',previous.status,'server_revision',previous.server_revision));
            continue;
        end if;
        if (select receipt_count from jojo_account_settings.accounts where owner_id=v_owner)>=100000 then
            raise exception using errcode='54000',message='ACCOUNT_RECEIPT_LIMIT';
        end if;
        select revision into current_revision from jojo_account_settings.entries
            where owner_id=v_owner and scope=v_scope and subject=v_subject and key=v_key;
        current_revision:=coalesce(current_revision,0);
        if expected<>current_revision then status:='conflict'; next_revision:=current_revision;
        else
            if v_scope='profile' then
                if v_key in ('profiles.home','profiles.iptv') then
                    v_domain:=substring(v_key from 10);
                    select * into existing from jojo_account_settings.profiles where owner_id=v_owner and id=v_subject;
                    if found then
                        if existing.domain<>v_domain or existing.primary_profile<>(v_value->>'primary')::boolean then
                            raise exception using errcode='22023',message='ACCOUNT_PROFILE_IDENTITY_IMMUTABLE';
                        end if;
                    else
                        if (select count(*) from jojo_account_settings.profiles where owner_id=v_owner and profiles.domain=v_domain)
                            >=(case when v_domain='home' then 5 else 20 end) then
                            raise exception using errcode='54000',message='ACCOUNT_PROFILE_LIMIT';
                        end if;
                        insert into jojo_account_settings.profiles(owner_id,id,domain,primary_profile)
                            values(v_owner,v_subject,v_domain,(v_value->>'primary')::boolean);
                    end if;
                elsif not exists(select 1 from jojo_account_settings.profiles where owner_id=v_owner and id=v_subject and profiles.domain='iptv') then
                    raise exception using errcode='42501',message='ACCOUNT_PROFILE_NOT_OWNED';
                end if;
            end if;
            if current_revision=0 and (select count(*) from jojo_account_settings.entries where owner_id=v_owner)>=10000 then
                raise exception using errcode='54000',message='ACCOUNT_SETTING_LIMIT';
            end if;
            update jojo_account_settings.accounts set head_revision=head_revision+1 where owner_id=v_owner returning head_revision into strict next_revision;
            insert into jojo_account_settings.entries(owner_id,scope,subject,key,value,revision,last_device)
                values(v_owner,v_scope,v_subject,v_key,v_value,next_revision,p_device)
                on conflict(owner_id,scope,subject,key) do update set value=excluded.value,revision=excluded.revision,
                    last_device=excluded.last_device,received_at=clock_timestamp();
            status:='applied';
        end if;
        insert into jojo_account_settings.receipts(owner_id,device_id,operation_id,body_hash,status,server_revision)
            values(v_owner,p_device,op,hash,status,next_revision);
        update jojo_account_settings.accounts set receipt_count=receipt_count+1 where owner_id=v_owner;
        result:=result||jsonb_build_array(jsonb_build_object('operation_id',op,'status',status,'server_revision',next_revision));
    end loop;
    return result;
end;
$$;

create function public.account_settings_seed(p_device uuid,p_proof text,p_operation uuid,p_ops jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid; account jojo_account_settings.accounts%rowtype; hash text; result jsonb;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    select * into strict account from jojo_account_settings.accounts where owner_id=v_owner for update;
    hash:=encode(sha256(convert_to(p_ops::text,'UTF8')),'hex');
    if p_operation is null or hash is null then raise exception using errcode='22023',message='ACCOUNT_SEED_INVALID'; end if;
    if account.initialized then
        if account.seed_operation=p_operation and account.seed_hash=hash then return jsonb_build_object('initialized',true); end if;
        raise exception using errcode='55000',message='ACCOUNT_ALREADY_INITIALIZED';
    end if;
    if not jojo_account_settings.seed_session_allowed() then
        raise exception using errcode='42501',message='ACCOUNT_SEED_REFERENCE_REQUIRED';
    end if;
    if exists(select 1 from jsonb_array_elements(p_ops) x where x->>'expected_revision'<>'0') then
        raise exception using errcode='22023',message='ACCOUNT_SEED_REVISION_INVALID';
    end if;
    result:=jojo_account_settings.apply_operations(p_device,p_ops,500);
    if exists(select 1 from jsonb_array_elements(result) x where x->>'status'<>'applied') then
        raise exception using errcode='55000',message='ACCOUNT_SEED_CONFLICT';
    end if;
    update jojo_account_settings.accounts set initialized=true,seed_operation=p_operation,seed_hash=hash where owner_id=v_owner;
    return jsonb_build_object('initialized',true);
end;
$$;

create function public.account_settings_read(p_device uuid,p_proof text,p_after bigint default 0) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid; account jojo_account_settings.accounts%rowtype; rows jsonb;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    -- Writers lock the same account row; SHARE keeps this head and its rows consistent.
    select * into strict account from jojo_account_settings.accounts where owner_id=v_owner for share;
    if p_after is null or p_after<0 or p_after>account.head_revision then
        raise exception using errcode='22023',message='ACCOUNT_CURSOR_INVALID';
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('scope',e.scope,'subject',e.subject,'key',e.key,'value',e.value,'revision',e.revision)
        order by e.revision),'[]'::jsonb) into rows from jojo_account_settings.entries e
        where e.owner_id=v_owner and e.revision>p_after and (e.scope<>'device' or e.subject=p_device);
    if jsonb_array_length(rows)>2000 then raise exception using errcode='54000',message='ACCOUNT_PAGE_LIMIT'; end if;
    return jsonb_build_object('schema',account.schema_version,'initialized',account.initialized,'head_revision',account.head_revision,'rows',rows);
end;
$$;

create function public.account_settings_write(p_device uuid,p_proof text,p_ops jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    perform 1 from jojo_account_settings.accounts where owner_id=v_owner and initialized for update;
    if not found then raise exception using errcode='55000',message='ACCOUNT_RESTORE_NOT_READY'; end if;
    return jojo_account_settings.apply_operations(p_device,p_ops,50);
end;
$$;

-- Supabase postgres is CREATEROLE, not superuser. Ownership transfer requires SET.
-- Keep inheritance disabled; remove SET again before committing this transaction.
grant jojo_account_settings_rpc to postgres with inherit false;
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_settings_register(uuid,text,text) owner to jojo_account_settings_rpc;
alter function public.account_settings_read(uuid,text,bigint) owner to jojo_account_settings_rpc;
alter function public.account_settings_seed(uuid,text,uuid,jsonb) owner to jojo_account_settings_rpc;
alter function public.account_settings_write(uuid,text,jsonb) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
grant jojo_account_settings_rpc to postgres with set false;
revoke all on all functions in schema jojo_account_settings from public,anon,authenticated;
grant execute on all functions in schema jojo_account_settings to jojo_account_settings_rpc;
revoke all on function public.account_settings_register(uuid,text,text),public.account_settings_read(uuid,text,bigint),
    public.account_settings_seed(uuid,text,uuid,jsonb),public.account_settings_write(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function public.account_settings_register(uuid,text,text),public.account_settings_read(uuid,text,bigint),
    public.account_settings_seed(uuid,text,uuid,jsonb),public.account_settings_write(uuid,text,jsonb) to authenticated;
commit;
