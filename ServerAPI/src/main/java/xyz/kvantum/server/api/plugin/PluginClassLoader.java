/*
 *
 *    Copyright (C) 2017 IntellectualSites
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.kvantum.server.api.plugin;

import lombok.Synchronized;
import xyz.kvantum.server.api.exceptions.PluginException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A custom class loader used with plugins
 *
 * @author Citymonstret
 */
public final class PluginClassLoader extends URLClassLoader
{

    private final PluginLoader loader;
    private final PluginFile desc;
    private final File data;
    private final Map<String, Class> classes = new HashMap<>();
    Plugin plugin;
    private Plugin init;

    /**
     * Constructor
     *
     * @param loader PluginLoader Instance
     * @param desc   PluginFile For the plugin
     * @param file   Plugin Jar
     * @throws MalformedURLException If the jar location is invalid
     */
    PluginClassLoader(final PluginLoader loader, final PluginFile desc,
                      final File file) throws MalformedURLException
    {
        super( new URL[]{ file.toURI().toURL() }, loader.getClass()
                .getClassLoader() );
        this.loader = loader;
        this.desc = desc;
        this.data = new File( file.getParent(), desc.name );

        Class jar;
        Class plg;
        try
        {
            jar = Class.forName( desc.mainClass, true, this );
        } catch ( final ClassNotFoundException e )
        {
            throw new PluginException( "Could not find main class for plugin " + desc.name + ", main class: " + desc
                    .mainClass );
        }
        try
        {
            plg = jar.asSubclass( Plugin.class );
        } catch ( final ClassCastException e )
        {
            throw new PluginException( "Plugin main class for " + desc.name + " is not instanceof Plugin" );
        }
        try
        {
            plugin = (Plugin) plg.newInstance();
        } catch ( InstantiationException | IllegalAccessException e )
        {
            e.printStackTrace();
        }
    }

    public File getData()
    {
        return this.data;
    }

    /**
     * Load a jar file into [this] instance
     *
     * @param file Jar file
     */
    void loadJar(final File file) throws MalformedURLException
    {
        if ( !file.getName().endsWith( ".jar" ) )
        {
            throw new IllegalArgumentException(
                    file.getName() + " is of wrong type" );
        }
        super.addURL( file.toURI().toURL() );
    }

    @Override
    protected Class<?> findClass(final String name)
            throws ClassNotFoundException
    {
        return this.findClass( name, true );
    }

    Class<?> findClass(final String name, final boolean global)
            throws ClassNotFoundException
    {
        Class<?> clazz = null;
        if ( classes.containsKey( name ) )
        {
            clazz = classes.get( name );
        }
        else
        {
            if ( global )
            {
                clazz = loader.getClassByName( name );
            }
            if ( clazz == null )
            {
                clazz = super.findClass( name );
                if ( clazz != null )
                {
                    loader.setClass( name, clazz );
                }
            }
            classes.put( name, clazz );
        }
        return clazz;
    }

    Set<String> getClasses()
    {
        return classes.keySet();
    }

    @Synchronized
    void create(final Plugin plugin)
    {
        if ( init != null )
        {
            throw new PluginException( plugin.getName() + " is already created" );
        }
        init = plugin;
        plugin.create( desc, data, this );
    }

    PluginFile getDesc()
    {
        return desc;
    }
}
