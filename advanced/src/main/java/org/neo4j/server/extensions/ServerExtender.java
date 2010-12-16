/**
 * Copyright (c) 2002-2010 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.neo4j.server.extensions;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.helpers.collection.NestingIterable;

public final class ServerExtender
{
    @SuppressWarnings( "unchecked" )
    private final Map<Class<?>, Map<String, ExtensionPoint>> extensions = new HashMap();

    ServerExtender()
    {
        extensions.put( Node.class, new ConcurrentHashMap<String, ExtensionPoint>() );
        extensions.put( Relationship.class, new ConcurrentHashMap<String, ExtensionPoint>() );
        extensions.put( GraphDatabaseService.class, new ConcurrentHashMap<String, ExtensionPoint>() );
    }

    Iterable<ExtensionPoint> getExtensionsFor( Class<?> type )
    {
        Map<String, ExtensionPoint> ext = extensions.get( type );
        if ( ext == null ) return Collections.emptyList();
        return ext.values();
    }

    Iterable<ExtensionPoint> all()
    {
        return new NestingIterable<ExtensionPoint, Map<String, ExtensionPoint>>(
                extensions.values() )
        {
            @Override
            protected Iterator<ExtensionPoint> createNestedIterator(
                    Map<String, ExtensionPoint> item )
            {
                return item.values().iterator();
            }
        };
    }

    ExtensionPoint getExtensionPoint( Class<?> type, String method )
            throws ExtensionLookupException
    {
        Map<String, ExtensionPoint> ext = extensions.get( type );
        ExtensionPoint extension = null;
        if ( ext != null )
        {
            extension = ext.get( method );
        }
        if ( extension == null )
        {
            throw new ExtensionLookupException( "No extension \"" + method + "\" for " + type );
        }
        return extension;
    }

    void addExtension( Class<?> type, ExtensionPoint extension )
    {
        Map<String, ExtensionPoint> ext = extensions.get( type );
        if ( ext == null ) throw new IllegalStateException( "Cannot extend " + type );
        add( ext, extension );
    }

    public void addGraphDatabaseExtensions( ExtensionPoint extension )
    {
        add( extensions.get( GraphDatabaseService.class ), extension );
    }

    public void addNodeExtensions( ExtensionPoint extension )
    {
        add( extensions.get( Node.class ), extension );
    }

    public void addRelationshipExtensions( ExtensionPoint extension )
    {
        add( extensions.get( Relationship.class ), extension );
    }

    private static void add( Map<String, ExtensionPoint> extensions, ExtensionPoint extension )
    {
        if ( extensions.get( extension.name() ) != null )
        {
            throw new IllegalArgumentException(
                    "This extension already has an extension point with the name \""
                            + extension.name() + "\"" );
        }
        extensions.put( extension.name(), extension );
    }
}
