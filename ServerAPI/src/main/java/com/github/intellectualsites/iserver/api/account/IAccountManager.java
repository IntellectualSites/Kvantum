/*
 * IntellectualServer is a web server, written entirely in the Java language.
 * Copyright (C) 2017 IntellectualSites
 *
 * This program is free software; you can redistribute it andor modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.github.intellectualsites.iserver.api.account;

import com.github.intellectualsites.iserver.api.core.ServerImplementation;
import com.github.intellectualsites.iserver.api.session.ISession;
import com.github.intellectualsites.iserver.api.util.ApplicationStructure;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Optional;

@SuppressWarnings("ALL")
/**
 * Manages {@link Account} and depends on {@link ApplicationStructure}
 */
public interface IAccountManager
{

    static final String SESSION_ACCOUNT_CONSTANT = "__user_id__";

    /**
     * Check if a given password matches the real password
     *
     * @param candidate Candidate password
     * @param password  Real password
     * @return true if the passwords are matching
     */
    default boolean checkPassword(final String candidate, final String password)
    {
        return BCrypt.checkpw( candidate, password );
    }

    /**
     * Get the container {@link ApplicationStructure}
     * @return {@link ApplicationStructure} implemenation
     */
    ApplicationStructure getApplicationStructure();

    /**
     * Setup the account manager
     * @throws Exception if anything goes wrong
     */
    void setup() throws Exception;

    /**
     * Create an {@link Account}
     * @param username Account username
     * @param password Account password
     * @return {@link Optional} containing the account if it was created successfully,
     *                          otherwise an empty optional ({@link Optional#empty()} is returned.
     */
    Optional<Account> createAccount(String username, String password);

    /**
     * Get an {@link Account} by username.
     * @param username Account username
     * @return {@link Optional} containing the account if it exsists
     *                          otherwise an empty optional ({@link Optional#empty()} is returned.
     */
    Optional<Account> getAccount(String username);

    /**
     * Get an {@link Account} by ID.
     * @param accountId Account ID
     * @return {@link Optional} containing the account if it exsists
     *                          otherwise an empty optional ({@link Optional#empty()} is returned.
     */
    Optional<Account> getAccount(int accountId);

    /**
     * Get an {@link Account} by session.
     * @param session session.
     * @return {@link Optional} containing the account if it exsists
     *                          otherwise an empty optional ({@link Optional#empty()} is returned.
     */
    default Optional<Account> getAccount(final ISession session)
    {
        if ( !session.contains( SESSION_ACCOUNT_CONSTANT ) )
        {
            return Optional.empty();
        }
        return getAccount( (int) session.get( SESSION_ACCOUNT_CONSTANT ) );
    }

    /**
     * Bind an {@link Account} to a {@link ISession}
     * @param account Account
     * @param session Session
     */
    default void bindAccount(final Account account, final ISession session)
    {
        session.set( SESSION_ACCOUNT_CONSTANT, account.getId() );
    }

    /**
     * Unbind any account from a {@link ISession}
     * @param session Session to be unbound
     */
    default void unbindAccount(final ISession session)
    {
        session.set( SESSION_ACCOUNT_CONSTANT, null );
    }

    /**
     * Set the data for an account
     * @param account Account
     * @param key Data key
     * @param value Data value
     */
    void setData(Account account, String key, String value);

    /**
     * Remove a data value from an account
     * @param account Account
     * @param key Data key
     */
    void removeData(Account account, String key);

    /**
     * Load the data into a {@link Account}
     * @param account Account to be loaded
     */
    void loadData(final Account account);

    default void checkAdmin()
    {
        if ( !getAccount( "admin" ).isPresent() )
        {
            Optional<Account> adminAccount = createAccount( "admin", "admin" );
            if ( !adminAccount.isPresent() )
            {
                ServerImplementation.getImplementation().log( "Failed to create admin account :(" );
            } else
            {
                ServerImplementation.getImplementation().log( "Created admin account with password \"admin\"" );
                adminAccount.get().setData( "administrator", "true" );
            }
        }
    }
}