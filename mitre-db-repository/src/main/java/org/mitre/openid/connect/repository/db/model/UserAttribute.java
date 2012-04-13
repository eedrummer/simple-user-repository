/****************************************************************************************
 *  UserAttribute.java
 *
 *  Created: Apr 2, 2012
 *
 *  @author DRAND
 *
 *  (C) Copyright MITRE Corporation 2012
 *
 *  The program is provided "as is" without any warranty express or implied, including
 *  the warranty of non-infringement and the implied warranties of merchantibility and
 *  fitness for a particular purpose.  The Copyright owner will not be liable for any
 *  damages suffered by you as a result of using the Program.  In no event will the
 *  Copyright owner be liable for any special, indirect or consequential damages or
 *  lost profits even if the Copyright owner has been advised of the possibility of
 *  their occurrence.
 *
 ***************************************************************************************/
package org.mitre.openid.connect.repository.db.model;

import java.sql.Date;

import javax.persistence.Basic;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

import org.eclipse.persistence.indirection.ValueHolderInterface;

@Entity
@Table(name = "USER_ATTRIBUTES")
public class UserAttribute {
	/**
	 * Regular attribute value attribute
	 */
	public static final short NORMAL_TYPE = 0;
	/**
	 * A named referenced to a remote link stored in the value. The access
	 * token is needed to obtain the data. The expiration is the expiration
	 * of the access token. The protocol used to obtain the data is OAUTH2.
	 */
	public static final short REMOTE_TYPE = 1;
	
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	Long id;
	
	@Basic
	@Column(name = "ATTR_NAME", length = 32)
	String name;
	
	@Basic
	@Column(name = "USER_ID")
	Long user_id;
	
	@Basic
	@Column(name = "ATTR_TYPE")
	Short type;
	
	/**
	 * Value may be a literal value or a link to a value. If a link then there
	 * may be an access token
	 */
	@Basic
	@Column(name = "ATTR_VALUE", length = 2000)
	String value;
	
	@Basic
	@Column(name = "ACCESS_TOKEN", length = 512, nullable = true)
	String access_token;
	
	@Basic
	@Column(name = "TOKEN_EXPIRATION", nullable = true)
	Date expiration;

	/**
	 * Empty ctor
	 */
	public UserAttribute() {
		// Intentionally empty
	}
	
	/**
	 * Ctor
	 * @param name a non-empty name for the user attribute
	 * @param value
	 */
	public UserAttribute(String name, String value) {
		setName(name);
		setValue(value);
		setType(NORMAL_TYPE);
	}
	
	/**
	 * @return the id
	 */
	public Long getId() {
		return id;
	}

	/**
	 * @return the name of the user attribute, which may be one of the well
	 * known names, or an extended attribute, never <code>null</code> or
	 * empty.
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		if (name == null || name.trim().length() == 0) {
			throw new IllegalArgumentException(
					"name should never be null or empty");
		}
		this.name = name;
	}

	/**
	 * @return the type, a type of 
	 */
	public Short getType() {
		return type;
	}

	/**
	 * @param type the type to set
	 */
	public void setType(Short type) {
		this.type = type;
	}

	/**
	 * @return the value
	 */
	public String getValue() {
		return value;
	}

	/**
	 * @param value the value to set
	 */
	public void setValue(String value) {
		this.value = value;
	}

	/**
	 * @return the access_token
	 */
	public String getAccess_token() {
		return access_token;
	}

	/**
	 * @param access_token the access_token to set
	 */
	public void setAccess_token(String access_token) {
		this.access_token = access_token;
	}

	/**
	 * @return the expiration
	 */
	public Date getExpiration() {
		return expiration;
	}

	/**
	 * @param expiration the expiration to set
	 */
	public void setExpiration(Date expiration) {
		this.expiration = expiration;
	}

	/**
	 * @return the user_id
	 */
	public Long getUserId() {
		return user_id;
	}

	/**
	 * @param user_id the user_id to set
	 */
	public void setUserId(Long user_id) {
		this.user_id = user_id;
	}

	/*
	 * The hashCode and equals method exclude user_id intentionally
	 */
	
	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((access_token == null) ? 0 : access_token.hashCode());
		result = prime * result
				+ ((expiration == null) ? 0 : expiration.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((type == null) ? 0 : type.hashCode());
		result = prime * result + ((value == null) ? 0 : value.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		UserAttribute other = (UserAttribute) obj;
		if (access_token == null) {
			if (other.access_token != null)
				return false;
		} else if (!access_token.equals(other.access_token))
			return false;
		if (expiration == null) {
			if (other.expiration != null)
				return false;
		} else if (!expiration.equals(other.expiration))
			return false;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (type == null) {
			if (other.type != null)
				return false;
		} else if (!type.equals(other.type))
			return false;
		if (value == null) {
			if (other.value != null)
				return false;
		} else if (!value.equals(other.value))
			return false;
		return true;
	}	
}
