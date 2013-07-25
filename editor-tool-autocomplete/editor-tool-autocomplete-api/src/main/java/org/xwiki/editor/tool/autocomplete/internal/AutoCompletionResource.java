/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.editor.tool.autocomplete.internal;

import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.VelocityContext;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.editor.tool.autocomplete.AutoCompletionMethodFinder;
import org.xwiki.editor.tool.autocomplete.HintData;
import org.xwiki.editor.tool.autocomplete.Hints;
import org.xwiki.editor.tool.autocomplete.TargetContent;
import org.xwiki.editor.tool.autocomplete.TargetContentLocator;
import org.xwiki.editor.tool.autocomplete.TargetContentType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rest.XWikiRestComponent;
import org.xwiki.velocity.VelocityManager;
import org.xwiki.velocity.internal.util.InvalidVelocityException;
import org.xwiki.velocity.internal.util.VelocityParser;
import org.xwiki.velocity.internal.util.VelocityParserContext;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.web.Utils;

/**
 * REST Resource for returning autocompletion hints. The content to autocomplete is passed in the request body, the
 * position of the cursor and the syntax in which the content is written in are passed as request parameters.
 * 
 * @version $Id$
 */
@Component("org.xwiki.editor.tool.autocomplete.internal.AutoCompletionResource")
@Path("/autocomplete")
public class AutoCompletionResource implements XWikiRestComponent
{
    /**
     * Used to get the Velocity Context from which we retrieve the list of bound variables that are used for
     * autocompletion.
     */
    @Inject
    private VelocityManager velocityManager;

    /**
     * Used to dynamically find Autocompletion Method finder to handle specific cases.
     * 
     * @see AutoCompletionMethodFinder
     */
    @Inject
    private ComponentManager componentManager;

    /**
     * Used to autodiscover method hints.
     */
    @Inject
    private AutoCompletionMethodFinder defaultAutoCompletionMethodFinder;

    /**
     * Used to extract the content and the type under the cursor position.
     */
    @Inject
    private TargetContentLocator targetContentLocator;

    /**
     * Logging framework.
     */
    @Inject
    private Logger logger;

    /**
     * A Velocity Parser that we use to help parse Velocity content for figuring out autocompletion.
     */
    private VelocityParser parser = new VelocityParser();

    /**
     * Main REST entry point for getting Autocompletion hints.
     * 
     * @param offset the position of the cursor in the full content
     * @param syntaxId the syntax in which the content is written in
     * @param content the full wiki content
     * @return the list of autocompletion hints
     */
    @POST
    public Hints getAutoCompletionHints(@QueryParam("offset") int offset, @QueryParam("syntax") String syntaxId,
        String content)
    {
        Hints hints = new Hints();

        // Only support autocompletion on Velocity ATM
        TargetContent targetContent = this.targetContentLocator.locate(content, syntaxId, offset);
        if (targetContent.getType() == TargetContentType.VELOCITY) {
            hints = getHints(targetContent.getContent(), targetContent.getPosition());
        }

        // Subtract the temporary user input size from the initial offset to get the absolute start offset of the user's
        // input.
        hints.withStartOffset(offset - hints.getStartOffset());

        return hints;
    }

    /**
     * @param content the Velocity content to autocomplete
     * @param offset the position of the cursor relative to the Velocity content
     * @return the list of autocompletion hints
     */
    private Hints getHints(String content, int offset)
    {
        Hints results = new Hints();
        char[] chars = content.toCharArray();
        VelocityContext velocityContext = getVelocityContext();

        // Find the dollar sign before the current position
        int dollarPos = StringUtils.lastIndexOf(content, '$', offset);
        if (dollarPos > -1) {
            // Special case for when there's no variable after the dollar position since the Velocity Parser doesn't
            // support parsing this case.
            if (isCursorDirectlyAfterDollar(chars, dollarPos, offset)) {
                // Find all objects bound to the Velocity Context. We need to also look in the chained context since
                // this is where we store Velocity Tools
                results = getVelocityContextKeys("", velocityContext);
            } else {
                StringBuffer velocityBlock = new StringBuffer();
                VelocityParserContext context = new VelocityParserContext();
                try {
                    // Get the block after the dollar
                    int blockPos = this.parser.getVar(chars, dollarPos, velocityBlock, context);
                    // if newPos matches the current position then it means we have a valid block for autocompletion

                    // Note: we need to handle the special case where the cursor is just after the dot since getVar
                    // will not return it!
                    if (blockPos + 1 == offset && chars[blockPos] == '.') {
                        blockPos++;
                        velocityBlock.append('.');
                    }

                    if (blockPos == offset) {
                        results = getMethodsOrVariableHints(chars, dollarPos, blockPos, offset, velocityContext);
                    }

                } catch (InvalidVelocityException e) {
                    this.logger.debug("Failed to get autocomplete hints for content [{}] at offset [{}]", 
                        new Object[] {content, offset, e});
                }
            }
        }

        return results;
    }

    /**
     * @param chars the content to parse
     * @param dollarPos the position of the dollar symbol
     * @param offset the position in the whole content of the cursor
     * @return false if there's a velocity variable after the dollar sign
     */
    private boolean isCursorDirectlyAfterDollar(char[] chars, int dollarPos, int offset)
    {
        boolean result = true;

        for (int i = dollarPos; i < offset; i++) {
            if (chars[i] != '$' && chars[i] != '!' && chars[i] != '{') {
                result = false;
                break;
            }
        }

        return result;
    }

    /**
     * @param chars the content to parse
     * @param dollarPos the position of the dollar symbol
     * @param blockPos the position in the content after the whole fragment starting with the dollar symbol
     * @param offset the position in the whole content of the cursor
     * @param velocityContext the velocity context used to get the variables bound in the Velocity Context
     * @return the list of hints
     * @throws InvalidVelocityException when the code to parse is not what's expected
     */
    private Hints getMethodsOrVariableHints(char[] chars, int dollarPos, int blockPos, int offset,
        VelocityContext velocityContext) throws InvalidVelocityException
    {
        Hints results = new Hints();

        // Get the property before the first dot.
        int methodPos = -1;
        for (int i = dollarPos; i < offset; i++) {
            if (chars[i] == '.') {
                methodPos = i;
                break;
            }
        }

        // Get the variable name without any leading '$', '!' or '{'
        int endPos = (methodPos == -1) ? offset : methodPos;
        int variableStartPos = -1;
        for (int i = dollarPos; i < endPos; i++) {
            if (chars[i] != '$' && chars[i] != '!' && chars[i] != '{') {
                variableStartPos = i;
                break;
            }
        }
        if (variableStartPos > -1) {
            String variableName = new String(chars, variableStartPos, endPos - variableStartPos);

            if (methodPos > -1) {
                results = getMethods(chars, variableName, blockPos, methodPos, offset, velocityContext);
            } else {
                results = getVelocityContextKeys(variableName, velocityContext);

                // Set the temporary start offset as the size of the user's input.
                results.withStartOffset(variableName.length());
            }
        }

        return results;
    }

    /**
     * @param chars the content to parse
     * @param propertyName the name of the property on which we want to find methods
     * @param blockPos the position in the content after the whole fragment starting with the dollar symbol
     * @param methodPos the position in the content after the whole fragment starting with the dollar symbol
     * @param offset the position in the whole content of the cursor
     * @param velocityContext the velocity context used to get the variables bound in the Velocity Context
     * @return the list of hints
     * @throws InvalidVelocityException when the code to parse is not what's expected
     */
    private Hints getMethods(char[] chars, String propertyName, int blockPos, int methodPos, int offset,
        VelocityContext velocityContext) throws InvalidVelocityException
    {
        Hints results = new Hints();
        VelocityParserContext context = new VelocityParserContext();

        String fragment = "";
        boolean autoCompleteMethods = false;

        // Handle the case where the cursor is just after the dot
        if (methodPos + 1 == offset) {
            autoCompleteMethods = true;
        } else {
            StringBuffer propertyBlock = new StringBuffer();
            int newMethodPos = this.parser.getMethodOrProperty(chars, methodPos, propertyBlock, context);
            // Extract the method name without the parameters
            // Remove the leading dot
            String methodName = StringUtils.substringBefore(propertyBlock.toString(), "(").substring(1);
            if (newMethodPos == blockPos) {
                autoCompleteMethods = true;
                // Remove the leading dot
                fragment = methodName;
            } else {
                // TODO: Refactor this ugly code below and make it generic so that we can support any nesting level of
                // methods.

                // There is more! It probably means some chained method call...
                AutoCompletionMethodFinder methodFinder = getMethodFinder(propertyName);
                Object propertyClass = velocityContext.get(propertyName);
                List<Class> returnTypes = methodFinder.findMethodReturnTypes(propertyClass.getClass(), methodName);

                // Find the next method name...
                StringBuffer methodBlock = new StringBuffer();
                // Handle the case where the last char is a '.' since our Velocity Parser doesn't support that
                String newFragment;
                if (newMethodPos + 1 == chars.length && chars[newMethodPos] == '.') {
                    newFragment = "";
                } else {
                    int newMethodPos2 = this.parser.getMethodOrProperty(chars, newMethodPos, methodBlock, context);
                    newFragment = methodBlock.toString().substring(1);
                    results.withStartOffset(newMethodPos2 - newMethodPos - 1);
                }
                // Find all methods from return types matching the new fragment
                for (Class<?> returnType : returnTypes) {
                    results.withHints(this.defaultAutoCompletionMethodFinder.findMethods(returnType, newFragment));
                }
            }
        }

        if (autoCompleteMethods) {
            // Find methods using Reflection
            results = getMethods(propertyName, fragment, velocityContext);
            // Set the temporary start offset as the size of the user's input.
            results.withStartOffset(blockPos - methodPos - 1);
        }

        return results;
    }

    /**
     * Find out all Velocity variable names bound in the Velocity Context.
     * 
     * @param fragmentToMatch the prefix to filter with in order to return only variable whose names start with the
     *            passed string
     * @param velocityContext the Velocity Context from which to get the bound variables
     * @return the Velocity variables
     */
    private Hints getVelocityContextKeys(String fragmentToMatch, VelocityContext velocityContext)
    {
        Hints hints = new Hints();

        addVelocityKeys(hints, velocityContext.getKeys(), fragmentToMatch);
        if (velocityContext.getChainedContext() != null) {
            addVelocityKeys(hints, velocityContext.getChainedContext().getKeys(), fragmentToMatch);
        }

        return hints;
    }

    /**
     * Add variables to the passed results list.
     * 
     * @param results the list of variable names
     * @param keys the keys containing the variables to add
     * @param fragmentToMatch the filter in order to only add variable whose names start with the passed string
     */
    private void addVelocityKeys(Hints results, Object[] keys, String fragmentToMatch)
    {
        for (Object key : keys) {
            if (key instanceof String && ((String) key).startsWith(fragmentToMatch)) {
                results.withHints(new HintData((String) key, (String) key));
            }
        }
    }

    /**
     * @param variableName the variable name corresponding to the class for which to find the methods
     * @param fragmentToMatch the filter to only return methods matching the passed string
     * @param velocityContext the Velocity Context from which to get the Class corresponding to the variable name
     * @return the method names found in the Class pointed to by the passed variableName name
     */
    private Hints getMethods(String variableName, String fragmentToMatch, VelocityContext velocityContext)
    {
        Hints hints = new Hints();
        Object propertyClass = velocityContext.get(variableName);

        if (propertyClass != null) {
            // Allow special handling for classes that have registered a custom introspection handler
            if (this.componentManager.hasComponent(AutoCompletionMethodFinder.class, variableName)) {
                try {
                    AutoCompletionMethodFinder finder =
                        this.componentManager.getInstance(AutoCompletionMethodFinder.class, variableName);
                    hints.withHints(finder.findMethods(propertyClass.getClass(), fragmentToMatch));
                } catch (ComponentLookupException e) {
                    // Component not found, continue with default finder...
                }
            }

            if (hints.isEmpty()) {
                hints.withHints(this.defaultAutoCompletionMethodFinder.findMethods(propertyClass.getClass(),
                    fragmentToMatch));
            }
        }

        return hints;
    }

    /**
     * @param hint the hint of the finder to return. If no such component exist return the default finder
     * @return the {@link AutoCompletionMethodFinder} to use depending on the passed hint
     */
    private AutoCompletionMethodFinder getMethodFinder(String hint)
    {
        AutoCompletionMethodFinder finder = null;

        // Allow special handling for classes that have registered a custom introspection handler
        if (this.componentManager.hasComponent(AutoCompletionMethodFinder.class, hint)) {
            try {
                finder = this.componentManager.getInstance(AutoCompletionMethodFinder.class, hint);
            } catch (ComponentLookupException e) {
                // Component not found, continue with default finder...
            }
        }

        if (finder == null) {
            finder = this.defaultAutoCompletionMethodFinder;
        }

        return  finder;
    }

    /**
     * @return the Velocity Context used to find existing bound variables
     */
    protected VelocityContext getVelocityContext()
    {
        VelocityContext velocityContext = this.velocityManager.getVelocityContext();

        // We add the "doc", "tdoc" and "sdoc" mappings since we don't get them from the Velocity Manager as they are
        // normally added based on the document passed in the request. However since we return the same method names
        // whatever the doc, we can manually add them.
        XWikiDocument fakeDocument = createFakeXWikiDocument();
        velocityContext.put("doc", fakeDocument);
        velocityContext.put("sdoc", fakeDocument);
        velocityContext.put("tdoc", fakeDocument);

        return velocityContext;
    }

    /**
     * @return a fake XWiki Document instance
     */
    protected XWikiDocument createFakeXWikiDocument()
    {
        // Note: Creating an XWikiDocument instance requires that the static component manager be set up
        // unfortunately...
        Utils.setComponentManager(this.componentManager);
        return new XWikiDocument(new DocumentReference("notusedwiki", "notusedspace", "notusedpage"));
    }
}
