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
package org.xwiki.editor.tool.autocomplete;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * Represents the content and type at the cursor position (e.g. the whole content could be written in XWiki Syntax
 * Syntax 2.1 and the content at the cursor position could be Velocity content inside a {@code {{velocity}}} macro).
 * Also provides the position of the cursor relative to the content.
 *
 * @version $Id$
 */
public class TargetContent
{
    /**
     * @see #getContent()
     */
    private String content;

    /**
     * @see #getType()
     */
    private TargetContentType type;

    /**
     * @see #getPosition()
     */
    private int position;

    /**
     * @see #getContextData()
     */
    private Object contextData;

    /**
     * @param content see {@link #getContent()}
     * @param position see {@link #getPosition()}
     * @param type see {@link #getType()}
     */
    public TargetContent(String content, int position, TargetContentType type)
    {
        this.content = content;
        this.position = position;
        this.type = type;
    }

    /**
     * @param content see {@link #getContent()}
     * @param position see {@link #getPosition()}
     * @param type see {@link #getType()}
     * @param contextData see {@link #getContextData()}
     */
    public TargetContent(String content, int position, TargetContentType type, Object contextData)
    {
        this.content = content;
        this.position = position;
        this.type = type;
        this.contextData = contextData;
    }

    /**
     * @return the content
     */
    public String getContent()
    {
        return this.content;
    }

    /**
     * @return the content type (velocity, etc)
     */
    public TargetContentType getType()
    {
        return this.type;
    }

    /**
     * @return the context data (specific to the {@link HintsFinder}, for example for the Velocity Hints Finder, it
     *         contains the velocity content for all the velocity rendering macros before the current one that has the
     *         cursor). Can be null.
     */
    public Object getContextData()
    {
        return this.contextData;
    }

    /**
     * @return the current position of the cursor relative to the content
     */
    public int getPosition()
    {
        return this.position;
    }

    @Override
    public boolean equals(Object object)
    {
        if (object == null) {
            return false;
        }
        if (object == this) {
            return true;
        }
        if (object.getClass() != getClass()) {
            return false;
        }
        TargetContent rhs = (TargetContent) object;
        EqualsBuilder builder = new EqualsBuilder()
            .append(this.content, rhs.content)
            .append(this.type, rhs.type)
            .append(this.position, rhs.position);

        if (getContextData() != null) {
            builder.append(getContextData(), rhs.getContextData());
        }

        return builder.isEquals();
    }

    @Override
    public int hashCode()
    {
        HashCodeBuilder builder = new HashCodeBuilder(5, 5)
            .append(this.content)
            .append(this.type)
            .append(this.position);

        if (getContextData() != null) {
            builder.append(getContextData());
        }

        return builder.toHashCode();
    }
}
