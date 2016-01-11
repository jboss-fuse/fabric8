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

import io.fabric8.patch.management.impl.Activator;
import org.apache.commons.io.FileUtils;
import org.osgi.service.log.LogService;

public class ChooseUserVersionResolver implements Resolver {

    @Override
    public String resolve(File patchChange, File base, File userChange) {
        try {
            return FileUtils.readFileToString(userChange);
        } catch (IOException e) {
            Activator.log(LogService.LOG_ERROR, null, "Problem resolving conflict: " + e.getMessage(), e, true);
        }
        return null;
    }

}
