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

import java.util.regex.Pattern;

public class SetEnvBatResolver extends SetEnvResolver {

    private static final Pattern MEMORY_SETTINGS = Pattern.compile("^\\s*SET\\s*([A-Z_]+\\s*=\\s*[0-9]+[kKmMgG]).*$");

    protected Pattern getMemorySettingsPattern() {
        return MEMORY_SETTINGS;
    }

    @Override
    public String toString() {
        return "bin/setenv.bat resolver";
    }

    @Override
    protected String getEOL() {
        return "\r\n";
    }

}
