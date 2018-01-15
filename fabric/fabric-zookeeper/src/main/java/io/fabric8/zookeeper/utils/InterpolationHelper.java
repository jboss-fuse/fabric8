/**
 *  Copyright 2005-2016 Red Hat, Inc.
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
package io.fabric8.zookeeper.utils;

import java.util.Deque;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;

import org.osgi.framework.BundleContext;

/**
 * <p>
 * Enhancement of the standard <code>Properties</code>
 * managing the maintain of comments, etc.
 * </p>
 *
 */
public class InterpolationHelper {

    public static final String MARKER = "$__";

    private InterpolationHelper() {
    }

    private static final char ESCAPE_CHAR = '\\';
    private static final String DELIM_START = "${";
    private static final String DELIM_STOP = "}";


    /**
     * Callback for substitution
     */
    public interface SubstitutionCallback {

        String getValue(String key);

    }

    /**
     * Perform substitution on a property set
     *
     * @param properties the property set to perform substitution on
     */
    public static void performSubstitution(Map<String, String> properties) {
        performSubstitution(properties, (BundleContext) null);
    }

    /**
     * Perform substitution on a property set
     *
     * @param properties the property set to perform substitution on
     */
    public static void performSubstitution(Map<String, String> properties, final BundleContext context) {
        performSubstitution(properties, new SubstitutionCallback() {
            public String getValue(String key) {
                String value = null;
                if (context != null) {
                    value = context.getProperty(key);
                }
                if (value == null) {
                    value = System.getProperty(value, "");
                }
                return value;
            }
        });
    }

    /**
     * Perform substitution on a property set
     *
     * @param properties the property set to perform substitution on
     */
    public static void performSubstitution(Map<String, String> properties, SubstitutionCallback callback) {
        for (String name : properties.keySet()) {
            String value = properties.get(name);
            properties.put(name, substVars(value, name, null, properties, callback));
        }
    }


    /**
     * <p>
     * This method performs property variable substitution on the
     * specified value. If the specified value contains the syntax
     * <tt>${&lt;prop-name&gt;}</tt>, where <tt>&lt;prop-name&gt;</tt>
     * refers to either a configuration property or a system property,
     * then the corresponding property value is substituted for the variable
     * placeholder. Multiple variable placeholders may exist in the
     * specified value as well as nested variable placeholders, which
     * are substituted from inner most to outer most. Configuration
     * properties override system properties.
     * </p>
     *
     * @param val         The string on which to perform property substitution.
     * @param currentKey  The key of the property being evaluated used to
     *                    detect cycles.
     * @param cycleMap    Map of variable references used to detect nested cycles.
     * @param configProps Set of configuration properties.
     * @param callback    the callback to obtain substitution values
     * @param defaultsToEmptyString    sets an empty string if a replacement value is not found, leaves intact otherwise
     * @return The value of the specified string after system property substitution.
     * @throws IllegalArgumentException If there was a syntax error in the
     *                                  property placeholder syntax or a recursive variable reference.
     */
    public static String substVars(String val,
                                   String currentKey,
                                   Map<String, String> cycleMap,
                                   Map<String, String> configProps,
                                   SubstitutionCallback callback,
                                   boolean defaultsToEmptyString)
            throws IllegalArgumentException {
        if (cycleMap == null) {
            cycleMap = new HashMap<String, String>();
        }

        // Put the current key in the cycle map.
        cycleMap.put(currentKey, currentKey);

        // Assume we have a value that is something like:
        // "leading ${foo.${bar}} middle ${baz} trailing"

        // Find the first ending '}' variable delimiter, which
        // will correspond to the first deepest nested variable
        // placeholder.
        int stopDelim = val.indexOf(DELIM_STOP);
        while (stopDelim > 0 && val.charAt(stopDelim - 1) == ESCAPE_CHAR) {
            stopDelim = val.indexOf(DELIM_STOP, stopDelim + 1);
        }

        // Find the matching starting "${" variable delimiter
        // by looping until we find a start delimiter that is
        // greater than the stop delimiter we have found.
        int startDelim = val.indexOf(DELIM_START);
        while (stopDelim >= 0) {
            int idx = val.indexOf(DELIM_START, startDelim + DELIM_START.length());
            if ((idx < 0) || (idx > stopDelim)) {
                break;
            } else if (idx < stopDelim) {
                startDelim = idx;
            }
        }

        // If we do not have a start or stop delimiter, then just
        // return the existing value.
        if ((startDelim < 0) || (stopDelim < 0)) {
            return unescape(val);
        }

        // TODO if we end up with startDelim bigger, lets just avoid throwing an exception
        // don't grok why we should end here though? Seems to happen with 2 consecutive expressions
        // e.g. see  JolokiaAgentHelperSubstituteTest.
        if (startDelim >= stopDelim) {
            return unescape(val);
        }

        // At this point, we have found a variable placeholder so
        // we must perform a variable substitution on it.
        // Using the start and stop delimiter indices, extract
        // the first, deepest nested variable placeholder.
        String variable = val.substring(startDelim + DELIM_START.length(), stopDelim);

        // Verify that this is not a recursive variable reference.
        if (cycleMap.get(variable) != null) {
            throw new IllegalArgumentException("recursive variable reference: " + variable);
        }

        // Get the value of the deepest nested variable placeholder.
        // Try to configuration properties first.
        String substValue = (String) ((configProps != null) ? configProps.get(variable) : null);
        if (substValue == null) {
            if (variable.length() <= 0) {
                substValue = "";
            } else {
                if (callback != null) {
                    substValue = callback.getValue(variable);
                }
                if (substValue == null) {
                    if (defaultsToEmptyString) {
                        substValue = System.getProperty(variable, "");
                    } else{
                        // alters the original token to avoid infinite recursion
                        // altered tokens are reverted in substVarsPreserveUnresolved()
                        substValue = System.getProperty(variable, MARKER + "{" + variable + "}");
                    }

                }
            }
        }

        // Remove the found variable from the cycle map, since
        // it may appear more than once in the value and we don't
        // want such situations to appear as a recursive reference.
        cycleMap.remove(variable);

        // Append the leading characters, the substituted value of
        // the variable, and the trailing characters to get the new
        // value.
        val = val.substring(0, startDelim) + substValue + val.substring(stopDelim + DELIM_STOP.length(), val.length());

        // Now perform substitution again, since there could still
        // be substitutions to make.
        if(defaultsToEmptyString) {
            val = substVars(val, currentKey, cycleMap, configProps, callback, defaultsToEmptyString);
        }else{
            val = substVarsPreserveUnresolved(val, currentKey, cycleMap, configProps, callback);
        }

        // Remove escape characters preceding {, } and \
        val = unescape(val);

        // Return the value.
        return val;
    }

    /**
     * <p>
     * This method performs property variable substitution on the
     * specified value. If the specified value contains the syntax
     * <tt>${&lt;prop-name&gt;}</tt>, where <tt>&lt;prop-name&gt;</tt>
     * refers to either a configuration property or a system property,
     * then the corresponding property value is substituted for the variable
     * placeholder. Multiple variable placeholders may exist in the
     * specified value as well as nested variable placeholders, which
     * are substituted from inner most to outer most. Configuration
     * properties override system properties.
     * If a corresponding replacement value is not found, an empty String will be used.
     * </p>
     *
     * @param val         The string on which to perform property substitution.
     * @param currentKey  The key of the property being evaluated used to
     *                    detect cycles.
     * @param cycleMap    Map of variable references used to detect nested cycles.
     * @param configProps Set of configuration properties.
     * @param callback    the callback to obtain substitution values
     * @return The value of the specified string after system property substitution.
     * @throws IllegalArgumentException If there was a syntax error in the
     *                                  property placeholder syntax or a recursive variable reference.
     */
    public static String substVars(String val,
                                   String currentKey,
                                   Map<String, String> cycleMap,
                                   Map<String, String> configProps,
                                   SubstitutionCallback callback)
            throws IllegalArgumentException {
                return substVars(   val,
                                    currentKey,
                                    cycleMap,
                                    configProps,
                                    callback,
                                    true);
    }


    /**
     * <p>
     * This method performs property variable substitution on the
     * specified value. If the specified value contains the syntax
     * <tt>${&lt;prop-name&gt;}</tt>, where <tt>&lt;prop-name&gt;</tt>
     * refers to either a configuration property or a system property,
     * then the corresponding property value is substituted for the variable
     * placeholder. Multiple variable placeholders may exist in the
     * specified value as well as nested variable placeholders, which
     * are substituted from inner most to outer most. Configuration
     * properties override system properties.
     * </p>
     *
     * @param val         The string on which to perform property substitution.
     * @param currentKey  The key of the property being evaluated used to
     *                    detect cycles.
     * @param cycleMap    Map of variable references used to detect nested cycles.
     * @param configProps Set of configuration properties.
     * @param callback    the callback to obtain substitution values
     * @return The value of the specified string after system property substitution.
     * @throws IllegalArgumentException If there was a syntax error in the
     *                                  property placeholder syntax or a recursive variable reference.
     */
    public static String substVarsPreserveUnresolved(String val,
                                                     String currentKey,
                                                     Map<String, String> cycleMap,
                                                     Map<String, String> configProps,
                                                     SubstitutionCallback callback)
            throws IllegalArgumentException {
            String result =  substVars(   val,
                    currentKey,
                    cycleMap,
                    configProps,
                    callback,
                    false);
            String subs =  (result != null) ? result.replaceAll("\\" + MARKER, "\\$"): result;
            return subs;
        }

    private static String unescape(String val) {
        int escape = val.indexOf(ESCAPE_CHAR);
        while (escape >= 0 && escape < val.length() - 1) {
            char c = val.charAt(escape + 1);
            if (c == '{' || c == '}' || c == ESCAPE_CHAR) {
                val = val.substring(0, escape) + val.substring(escape + 1);
            }
            escape = val.indexOf(ESCAPE_CHAR, escape + 1);
        }
        return val;
    }

    /**
     * <p>When overlay profile is read, escaped property placeholders are
     * {@link InterpolationHelper#unescape(java.lang.String) unescaped}. They should be escaped again before
     * storing in configadmin.</p>
     * <p>There are multiple processes involved:<ul>
     *     <li>fileinstall -&gt; configadmin</li>
     *     <li>configadmin -&gt; fileinstall</li>
     *     <li>overlay profile construction (with unescaping)</li>
     *     <li>reading from git profile</li>
     * </ul></p>
     * <p>see: https://issues.jboss.org/browse/ENTESB-7584</p>
     * @param configuration
     * @return whether any property was escaped
     */
    public static boolean escapePropertyPlaceholders(Hashtable<String, Object> configuration) {
        boolean anyEscape = false;
        for (Map.Entry<String, Object> entry : configuration.entrySet()) {
            if (entry.getValue() instanceof String && ((String) entry.getValue()).contains("${")) {
                // we don't assume stateful/context-dependent parsing
                String v = (String) entry.getValue();
                StringBuilder sb = new StringBuilder();
                char[] charArray = v.toCharArray();
                Deque<Boolean> stack = new LinkedList<>();
                for (int i = 0, charArrayLength = charArray.length; i < charArrayLength; i++) {
                    char c = charArray[i];
                    switch (c) {
                        case '$':
                            if (i < charArrayLength - 1 && charArray[i + 1] == '{') {
                                sb.append("$\\");
                                anyEscape = true;
                            }
                            break;
                        case '{':
                            stack.push(i > 0 && charArray[i - 1] == '$');
                            sb.append("{");
                            break;
                        case '}':
                            if (stack.peek() != null && stack.peek()) {
                                sb.append("\\");
                            }
                            stack.pop();
                            sb.append("}");
                            break;
                        default:
                            sb.append(c);
                    }
                }
                configuration.put(entry.getKey(), sb.toString());
            }
        }
        return anyEscape;
    }

}
