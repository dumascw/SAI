package com.aefyr.sai.installerx.resolver.meta.impl;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.aefyr.sai.R;
import com.aefyr.sai.installerx.Category;
import com.aefyr.sai.installerx.SplitApkSourceMeta;
import com.aefyr.sai.installerx.SplitCategory;
import com.aefyr.sai.installerx.SplitCategoryIndex;
import com.aefyr.sai.installerx.SplitPart;
import com.aefyr.sai.installerx.postprocessing.DeviceInfoAwarePostprocessor;
import com.aefyr.sai.installerx.resolver.appmeta.AppMeta;
import com.aefyr.sai.installerx.resolver.appmeta.AppMetaExtractor;
import com.aefyr.sai.installerx.resolver.appmeta.DefaultZipAppMetaExtractors;
import com.aefyr.sai.installerx.resolver.meta.ApkSourceFile;
import com.aefyr.sai.installerx.resolver.meta.ApkSourceMetaResolutionError;
import com.aefyr.sai.installerx.resolver.meta.ApkSourceMetaResolutionResult;
import com.aefyr.sai.installerx.resolver.meta.SplitApkSourceMetaResolver;
import com.aefyr.sai.installerx.splitmeta.BaseSplitMeta;
import com.aefyr.sai.installerx.splitmeta.FeatureSplitMeta;
import com.aefyr.sai.installerx.splitmeta.SplitMeta;
import com.aefyr.sai.installerx.splitmeta.config.AbiConfigSplitMeta;
import com.aefyr.sai.installerx.splitmeta.config.LocaleConfigSplitMeta;
import com.aefyr.sai.installerx.splitmeta.config.ScreenDestinyConfigSplitMeta;
import com.aefyr.sai.installerx.util.AndroidBinXmlParser;
import com.aefyr.sai.utils.IOUtils;
import com.aefyr.sai.utils.Stopwatch;
import com.aefyr.sai.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DefaultSplitApkSourceMetaResolver implements SplitApkSourceMetaResolver {
    private static final String TAG = "DSASMetaResolver";

    private static final String MANIFEST_FILE = "AndroidManifest.xml";

    private Context mContext;

    public DefaultSplitApkSourceMetaResolver(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public ApkSourceMetaResolutionResult resolveFor(ApkSourceFile apkSourceFile) throws Exception {
        Stopwatch sw = new Stopwatch();

        try {
            ApkSourceMetaResolutionResult result = parseViaParsingManifests(apkSourceFile);
            Log.d(TAG, String.format("Resolved meta for %s via parsing manifests in %d ms.", apkSourceFile.getName(), sw.millisSinceStart()));
            return result;
        } catch (Exception e) {
            //TODO alt parse
            throw e;
        }
    }

    private ApkSourceMetaResolutionResult parseViaParsingManifests(ApkSourceFile aApkSourceFile) throws Exception {
        try (ApkSourceFile apkSourceFile = aApkSourceFile) {
            AppMetaExtractor appMetaExtractor = DefaultZipAppMetaExtractors.fromArchiveExtension(mContext, Utils.getExtension(apkSourceFile.getName()));

            String packageName = null;
            String versionName = null;
            Long versionCode = null;
            boolean seenApk = false;
            boolean seenBaseApk = false;

            SplitCategoryIndex categoryIndex = new SplitCategoryIndex();

            ApkSourceFile.Entry entry;
            while ((entry = apkSourceFile.nextEntry()) != null) {
                if (!entry.getName().toLowerCase().endsWith(".apk")) {

                    if (appMetaExtractor != null && appMetaExtractor.wantEntry(entry))
                        appMetaExtractor.consumeEntry(entry, apkSourceFile.openEntryInputStream());

                    continue;
                }


                seenApk = true;
                boolean seenManifestElement = false;

                HashMap<String, String> manifestAttrs = new HashMap<>();

                ByteBuffer manifestBytes = stealManifestFromApk(apkSourceFile.openEntryInputStream());
                if (manifestBytes == null)
                    return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_no_manifest), true));

                AndroidBinXmlParser parser = new AndroidBinXmlParser(manifestBytes);
                int eventType = parser.getEventType();
                while (eventType != AndroidBinXmlParser.EVENT_END_DOCUMENT) {

                    if (eventType == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                        if (parser.getName().equals("manifest") && parser.getDepth() == 1 && parser.getNamespace().isEmpty()) {
                            if (seenManifestElement)
                                return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_dup_manifest_entry), true));

                            seenManifestElement = true;

                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                if (parser.getAttributeName(i).isEmpty())
                                    continue;

                                String namespace = "" + (parser.getAttributeNamespace(i).isEmpty() ? "" : (parser.getAttributeNamespace(i) + ":"));

                                manifestAttrs.put(namespace + parser.getAttributeName(i), parser.getAttributeStringValue(i));
                            }
                        }
                    }


                    eventType = parser.next();
                }

                if (!seenManifestElement)
                    return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_no_manifest_entry), true));

                SplitMeta splitMeta = SplitMeta.from(manifestAttrs);
                if (packageName == null) {
                    packageName = splitMeta.packageName();
                } else {
                    if (!packageName.equals(splitMeta.packageName()))
                        return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_pkg_mismatch), true));
                }
                if (versionCode == null) {
                    versionCode = splitMeta.versionCode();
                } else {
                    if (!versionCode.equals(splitMeta.versionCode()))
                        return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_version_mismatch), true));
                }

                if (splitMeta instanceof BaseSplitMeta) {
                    if (seenBaseApk)
                        return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_multiple_base_apks), true));

                    seenBaseApk = true;

                    BaseSplitMeta baseSplitMeta = (BaseSplitMeta) splitMeta;
                    versionName = baseSplitMeta.versionName();
                    categoryIndex.getOrCreate(Category.BASE_APK, getString(R.string.installerx_category_base_apk), null)
                            .addPart(new SplitPart(splitMeta, entry.getName(), entry.getLocalPath(), baseSplitMeta.packageName(), null, true, true));

                    continue;
                }

                if (splitMeta instanceof FeatureSplitMeta) {
                    FeatureSplitMeta featureSplitMeta = (FeatureSplitMeta) splitMeta;

                    categoryIndex.getOrCreate(Category.FEATURE, getString(R.string.installerx_category_dynamic_features), null)
                            .addPart(new SplitPart(splitMeta, entry.getName(), entry.getLocalPath(), getString(R.string.installerx_dynamic_feature, featureSplitMeta.module()), null, false, true));
                    continue;
                }

                if (splitMeta instanceof AbiConfigSplitMeta) {
                    AbiConfigSplitMeta abiConfigSplitMeta = (AbiConfigSplitMeta) splitMeta;

                    String name;
                    if (abiConfigSplitMeta.isForModule()) {
                        name = getString(R.string.installerx_split_config_abi_for_module, abiConfigSplitMeta.abi(), abiConfigSplitMeta.module());
                    } else {
                        name = getString(R.string.installerx_split_config_abi_for_base, abiConfigSplitMeta.abi());
                    }

                    categoryIndex.getOrCreate(Category.CONFIG_ABI, getString(R.string.installerx_category_config_abi), null)
                            .addPart(new SplitPart(splitMeta, entry.getName(), entry.getLocalPath(), name, null, false, false));
                    continue;
                }

                if (splitMeta instanceof LocaleConfigSplitMeta) {
                    LocaleConfigSplitMeta localeConfigSplitMeta = (LocaleConfigSplitMeta) splitMeta;

                    String name;
                    if (localeConfigSplitMeta.isForModule()) {
                        name = getString(R.string.installerx_split_config_locale_for_module, localeConfigSplitMeta.locale().getDisplayName(), localeConfigSplitMeta.module());
                    } else {
                        name = getString(R.string.installerx_split_config_locale_for_base, localeConfigSplitMeta.locale().getDisplayName());
                    }

                    categoryIndex.getOrCreate(Category.CONFIG_LOCALE, getString(R.string.installerx_category_config_locale), null)
                            .addPart(new SplitPart(splitMeta, entry.getName(), entry.getLocalPath(), name, null, false, false));
                    continue;
                }

                if (splitMeta instanceof ScreenDestinyConfigSplitMeta) {
                    ScreenDestinyConfigSplitMeta screenDestinyConfigSplitMeta = (ScreenDestinyConfigSplitMeta) splitMeta;

                    String name;
                    if (screenDestinyConfigSplitMeta.isForModule()) {
                        name = getString(R.string.installerx_split_config_dpi_for_module, screenDestinyConfigSplitMeta.densityName(), screenDestinyConfigSplitMeta.density(), screenDestinyConfigSplitMeta.module());
                    } else {
                        name = getString(R.string.installerx_split_config_dpi_for_base, screenDestinyConfigSplitMeta.densityName(), screenDestinyConfigSplitMeta.density());
                    }

                    categoryIndex.getOrCreate(Category.CONFIG_DENSITY, getString(R.string.installerx_category_config_dpi), null)
                            .addPart(new SplitPart(splitMeta, entry.getName(), entry.getLocalPath(), name, null, false, false));
                    continue;
                }

                categoryIndex.getOrCreate(Category.UNKNOWN, getString(R.string.installerx_category_unknown), getString(R.string.installerx_category_unknown_desc))
                        .addPart(new SplitPart(splitMeta, entry.getName(), entry.getLocalPath(), splitMeta.splitName(), null, false, true));

            }

            if (!seenApk)
                return ApkSourceMetaResolutionResult.failure(new ApkSourceMetaResolutionError(getString(R.string.installerx_dsas_meta_resolver_error_no_apks), true));


            new DeviceInfoAwarePostprocessor(mContext).process(categoryIndex);


            List<SplitCategory> splitCategoryList = categoryIndex.toList();
            Collections.sort(splitCategoryList, (o1, o2) -> Integer.compare(o1.category().ordinal(), o2.category().ordinal()));


            AppMeta appMeta;
            if (appMetaExtractor != null)
                appMeta = appMetaExtractor.buildMeta();
            else
                appMeta = new AppMeta();

            appMeta.packageName = packageName;
            appMeta.versionCode = versionCode;
            if (versionName != null)
                appMeta.versionName = versionName;


            return ApkSourceMetaResolutionResult.success(new SplitApkSourceMeta(appMeta, splitCategoryList, Collections.emptyList()));
        }
    }

    private String getString(@StringRes int id) {
        return mContext.getString(id);
    }

    private String getString(@StringRes int id, Object... formatArgs) {
        return mContext.getString(id, formatArgs);
    }

    @Nullable
    private ByteBuffer stealManifestFromApk(InputStream apkInputSteam) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(apkInputSteam)) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (!zipEntry.getName().equals(MANIFEST_FILE)) {
                    zipInputStream.closeEntry();
                    continue;
                }


                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                IOUtils.copyStream(zipInputStream, buffer);
                return ByteBuffer.wrap(buffer.toByteArray());
            }
        }

        return null;
    }

}