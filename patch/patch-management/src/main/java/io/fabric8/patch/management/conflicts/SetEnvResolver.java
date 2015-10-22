/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.patch.management.conflicts;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.management.impl.Activator;
import org.apache.commons.io.FileUtils;
import org.osgi.service.log.LogService;

/**
 * Conflict resolver for <code>bin/setenv</code> file - we prefer bigger memory settings
 */
public class SetEnvResolver implements Resolver {

    // see: https://docs.oracle.com/javase/8/docs/technotes/tools/unix/java.html#BABHDABI
    // "Append the letter k or K to indicate kilobytes, m or M to indicate megabytes, g or G to indicate gigabytes"
    private static final Pattern MEMORY_SETTINGS = Pattern.compile("^\\s*([A-Z_]+\\s*=\\s*[0-9]+[kKmMgG]).*$");

    @Override
    public String resolve(File patchChange, File base, File userChange) {
        Map<String, NumLitValue> patchSettings = new HashMap<>();
        Map<String, NumLitValue> userSettings = new HashMap<>();

        try {
            List<String> patchLines = FileUtils.readLines(patchChange);
            List<String> userLines = FileUtils.readLines(userChange);
            // 1st pass - collect settings
            collectSettings(patchSettings, patchLines);
            collectSettings(userSettings, userLines);

            // final version - based on patch version, but with possible memory settings from user
            StringBuffer sb = new StringBuffer();
            for (String pl : patchLines) {
                Matcher matcher = getMemorySettingsPattern().matcher(pl);
                if (matcher.matches()) {
                    String[] kv = matcher.group(1).split("\\s*=\\s*");
                    String key = kv[0].trim();
                    long pv = toBytes(kv[1].toUpperCase().trim());
                    NumLitValue uv = userSettings.get(key);
                    if (uv.value > pv) {
                        sb.append(pl.substring(0, matcher.start(1)));
                        sb.append(key).append("=").append(uv.literal);
                        sb.append(pl.substring(matcher.end(1)));
                        sb.append(getEOL());
                    } else {
                        sb.append(pl).append(getEOL());
                    }
                } else {
                    sb.append(pl).append(getEOL());
                }
            }
            return sb.toString();
        } catch (IOException e) {
            Activator.log(LogService.LOG_ERROR, null, "Problem resolving conflict: " + e.getMessage(), e, true);
        }
        return null;
    }

    @Override
    public String toString() {
        return "bin/setenv resolver";
    }

    protected Pattern getMemorySettingsPattern() {
        return MEMORY_SETTINGS;
    }

    protected String getEOL() {
        return "\n";
    }

    private void collectSettings(Map<String, NumLitValue> patchSettings, List<String> patchLines) {
        for (String pl : patchLines) {
            Matcher matcher = getMemorySettingsPattern().matcher(pl);
            if (matcher.matches()) {
                String[] kv = matcher.group(1).split("\\s*=\\s*");
                Long v = toBytes(kv[1].toUpperCase().trim());
                patchSettings.put(kv[0].trim(), new NumLitValue(v, kv[1].toUpperCase().trim()));
            }
        }
    }

    protected Long toBytes(String value) {
        long multiplier = 1;
        char c = value.charAt(value.length() - 1);
        switch (c) {
            case 'K':
                multiplier = 1024L;
                break;
            case 'M':
                multiplier = 1024L * 1024L;
                break;
            case 'G':
                multiplier = 1024L * 1024L * 1024L;
                break;
        }
        return Long.parseLong(value.substring(0, value.length() - 1)) * multiplier;
    }

    private static class NumLitValue {
        public long value;
        public String literal;

        public NumLitValue(long value, String literal) {
            this.value = value;
            this.literal = literal;
        }
    }

}
