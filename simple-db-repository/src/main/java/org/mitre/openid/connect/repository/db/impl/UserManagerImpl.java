/****************************************************************************************
 *  UserManagerImpl.java
 *
 *  Created: Jul 8, 2010
 *
 *  @author DRAND
 *
 *  (C) Copyright MITRE Corporation 2010
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
package org.mitre.openid.connect.repository.db.impl;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.sql.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.naming.AuthenticationException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.mitre.openid.connect.model.Address;
import org.mitre.openid.connect.model.UserInfo;
import org.mitre.openid.connect.repository.db.IPasswordRule;
import org.mitre.openid.connect.repository.db.IUserValidity;
import org.mitre.openid.connect.repository.db.LockedUserException;
import org.mitre.openid.connect.repository.db.PasswordException;
import org.mitre.openid.connect.repository.db.UserException;
import org.mitre.openid.connect.repository.db.UserManager;
import org.mitre.openid.connect.repository.db.model.Role;
import org.mitre.openid.connect.repository.db.model.User;
import org.mitre.openid.connect.repository.db.model.UserAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSender;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bean to manipulate user instances
 * 
 * @author DRAND
 */
@Transactional
@Repository
public class UserManagerImpl implements UserManager {
	private static final Logger logger = LoggerFactory
			.getLogger(UserManagerImpl.class);

	@PersistenceContext
	private EntityManager em;
	
	/**
	 * The default administrative user to create *if* there are no defined 
	 * admin users in the system on startup.
	 */
	private String defaultAdminUserName = null;
	/**
	 * Email for the default administrative user
	 */
	private String defaultAdminUserEmail = null;
	/**
	 * Rule that decides if a password is acceptable to the system. 
	 */
	private IPasswordRule passwordRule = null;
	/**
	 * Rule that decides if a user is acceptable to the system
	 */
	private IUserValidity userValidity = null;
	/**
	 * Secure random number generator. Do not replace with the regular random
	 * number generator. A pseudo random number generator will tend to produce
	 * evenly distributed but predictable patterns.
	 */
	private SecureRandom random = new SecureRandom();
	/**
	 * A separate component that isolates JavaMail.
	 */
	private MailSender mailer = null;
	/**
	 * The number of tries the user gets before we lock the user out of the system
	 * and require admin intervention.
	 */
	private int attemptLimit = 3;
	/**
	 * Base url that the web application will be placed at. There will be a set
	 * of predictable web components off of this base that handle password
	 * reset, login, and other tasks. This base url is used to create mail 
	 * messages that contain links to these pages.
	 */
	private URL base = null; 
	
	/**
	 * If the repository has no users that are administrators, create one based
	 * on the configuration. Also, create the admin role if it doesn't exist.
	 */
	public void testAndInitialize() {
		@SuppressWarnings("unchecked")
		Role admin = findRole("ADMIN");
		// Find admin users only
		TypedQuery<User> uq = (TypedQuery<User>) 
				em.createQuery("select u from User u inner join u.roles r where r.name = 'ADMIN'");
		List<User> users = uq.getResultList();
		if (users.size() == 0) {
			if (StringUtils.isBlank(defaultAdminUserName)) {
				logger.warn("Cannot create default user");
				return;
			}
			User defaultAdminUser = new User();
			defaultAdminUser.setUsername(defaultAdminUserName);
			defaultAdminUser.setEmail(defaultAdminUserEmail);
			String initialpw = "PassWord";
			int randomSalt = random.nextInt();
			try {
				String randomHash = salt(randomSalt, initialpw);
				defaultAdminUser.setPasswordHash(randomHash);
				defaultAdminUser.setPasswordSalt(randomSalt);
				defaultAdminUser.getRoles().add(admin);
				em.persist(defaultAdminUser);
			} catch (Exception e) {
				logger.error("Something's wrong that shouldn't be wrong", e);
			}
		}
		
		if (userValidity == null) {
			userValidity = new SimpleUserValidity();
		}
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#get(java.lang.String)
	 */
	public User get(String username) {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		TypedQuery<User> uq = (TypedQuery<User>) em.createQuery(
				"select u from User u where u.username = :username");
		List<User> results = uq.setParameter("username", username).getResultList();
		return results.size() > 0 ? results.get(0) : null;
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.UserManager#save(org.mitre.itflogin.model.User)
	 */
	public void save(User user) {
		if (user == null) {
			throw new IllegalArgumentException(
					"user should never be null");
		}
		user.setUpdated(new Date(System.currentTimeMillis()));
		if (user.getId() == null)
			em.persist(user);
		else
			em.merge(user);
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mitre.itflogin.UserManager#delete(java.lang.String)
	 */
	public void delete(String username) {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		User existing = get(username);
		if (existing != null) {
			em.remove(existing);
		} else {
			logger.warn("User could not be found: " + username);
		}
	}
	
	
	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.db.UserManager#deleteRole(java.lang.String)
	 */
	public void deleteRole(String rolename) {
		if (rolename == null || rolename.trim().length() == 0) {
			throw new IllegalArgumentException(
					"rolename should never be null or empty");
		}
		Role existing = findRole(rolename);
		if (existing != null) {
			em.remove(existing);
		} else {
			logger.warn("Role could not be found: " + rolename);
		}
		
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.UserManager#find(java.lang.String)
	 */
	@SuppressWarnings("unchecked")
	public List<User> find(String likePattern) {
		if (likePattern == null || likePattern.trim().length() == 0) {
			throw new IllegalArgumentException(
					"likePattern should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		TypedQuery<User> uq = (TypedQuery<User>) em.createQuery(
				"select u from User u where lower(u.username) like :pattern");
		List<User> results = uq.setParameter("pattern", likePattern.toLowerCase()).getResultList();
		return results;
	}
	
	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.db.UserManager#findInRange(int, int, org.mitre.openid.connect.repository.db.UserManager.SortBy)
	 */
	@SuppressWarnings("unchecked")
	public List<Map<String, String>> findInRange(int first, int count, SortBy sortBy) {
		List<User> users;
		if (sortBy.equals(SortBy.USERNAME) || sortBy.equals(SortBy.EMAIL)) {
			TypedQuery<User> uq = (TypedQuery<User>) em.createQuery(
					"select u from User u order by u." + sortBy.name().toLowerCase());
			users = uq.setFirstResult(first)
					.setMaxResults(count)
					.getResultList();
		} else {
			TypedQuery<User> uq = (TypedQuery<User>) em.createQuery(
					"select u from User u, UserAttribute ua " +
					"where u.id = ua.user_id and " +
					"ua.name = :attr order by ua.value");
			users = uq.setParameter("attr", sortBy.name())
					.setFirstResult(first)
					.setMaxResults(count)
					.getResultList();
		}
		List<Map<String,String>> rval = new ArrayList<Map<String,String>>();
		for(User u : users) {
			Map<String, String> data = new HashMap<String, String>();
			Query q = em.createNamedQuery("user_attributes_find_by_user_id");
			List<UserAttribute> attrs = q.setParameter("id", u.getId()).getResultList();
			for(UserAttribute attr : attrs) {
				if (attr.getType() != UserAttribute.NORMAL_TYPE) continue;
				data.put(attr.getName(), attr.getValue());
			}
			data.put("USERNAME", u.getUsername());
			data.put("EMAIL", u.getEmail());
			data.put("ID", u.getId().toString());
			rval.add(data);
		}
		return rval;
	}



	private final static String ROLE_Q_BY_NAME = "select r from Role r where r.name = :name";
	
	/* (non-Javadoc)
	 * @see org.mitre.itflogin.UserManager#find(java.lang.String)
	 */
	public Role findRole(String rolename) {
		if (rolename == null || rolename.trim().length() == 0) {
			throw new IllegalArgumentException(
					"rolename should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		TypedQuery<Role> rq = (TypedQuery<Role>) em.createQuery(ROLE_Q_BY_NAME);
		List<Role> found = rq.setParameter("name", rolename).getResultList();
		if (found.size() == 0) {
			Role role = new Role();
			role.setName(rolename);
			em.persist(role);
			return role;
		} else {
			return found.get(0);
		}
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#add(java.lang.String, java.lang.String)
	 */
	public void add(String username, String password) throws PasswordException,
			UserException {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		User existing = get(username);
		if (existing != null) {
			throw new UserException("User " + username + " already exists");
		}

		userValidity.valid(username);
		
		if (passwordRule != null)
			passwordRule.accept(password);

		User newUser = new User();
		newUser.setUsername(username);
		int psalt = random.nextInt();
		try {
			String phash = salt(psalt, password);
			newUser.setPasswordHash(phash);
			newUser.setPasswordSalt(psalt);
			em.persist(newUser);
		} catch (Exception e) {
			logger.error("Problem while storing user", e);
			throw new UserException(
					"User "
							+ username
							+ " was not stored due to system problem. Contact a developer to look at the logs.");
		}
	}
	
	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#checkConfirmation(java.lang.String, java.lang.String)
	 */
	public boolean checkConfirmation(String username, String confirmation) {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		if (confirmation == null || confirmation.trim().length() == 0) {
			throw new IllegalArgumentException(
					"confirmation should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		User user = get(username);
		if (user != null) {
			return false;
		}
		int psalt = user.getPasswordSalt();
		try {
			String chash = salt(psalt, confirmation);
			boolean confirmed = chash.equals(user.getConfirmationHash());
			if (confirmed && user.getEmailConfirmed() == false) {
				user.setEmailConfirmed(true);
				em.persist(user);
			}
			return confirmed;
		} catch (Exception e) {
			logger.error("Problem while confirming confirmation string", e);
			return false;
		}		
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#modifyPassword(java.lang.String, java.lang.String)
	 */
	public void modifyPassword(String username, String newpassword) throws UserException, PasswordException {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		User user = get(username);
		if (user == null) {
			throw new UserException("User " + username + " does not exist");
		}

		if (passwordRule != null)
			passwordRule.accept(newpassword);

		setUserPassword(username, newpassword, user);
	}

	/**
	 * Set the password for the given user
	 * @param username
	 * @param newpassword
	 * @param user
	 * @throws UserException
	 */
	private void setUserPassword(String username, String newpassword, User user)
			throws UserException {
		int psalt = random.nextInt();
		try {
			String phash = salt(psalt, newpassword);
			user.setPasswordHash(phash);
			user.setPasswordSalt(psalt);
			user.setConfirmationHash(null); // Assume that the user may have done a reset
			em.persist(user);
		} catch (Exception e) {
			logger.error("Problem while storing user", e);
			throw new UserException(
					"User "
							+ username
							+ " was not stored due to system problem. Contact a developer to look at the logs.");
		}
	}
	
	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#unlock(java.lang.String)
	 */
	public void unlock(String username) throws AuthenticationException {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		User user = get(username);
		if (user == null) {
			throw new AuthenticationException();
		}
		user.setFailedAttempts(0);
		em.persist(user);
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#authenticate(java.lang.String, java.lang.String)
	 */
	public void authenticate(String username, String password)
			throws AuthenticationException, LockedUserException {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		if (password == null) {
			password = "";
		}
		@SuppressWarnings("unchecked")
		User user = get(username);
		if (user == null) {
			throw new AuthenticationException();
		}
		if (user.getFailedAttempts() != null
				&& user.getFailedAttempts() >= attemptLimit) {
			throw new LockedUserException();
		}
		int psalt = user.getPasswordSalt();
		try {
			String phash = salt(psalt, password);
			if (!phash.equals(user.getPasswordHash())) {
				int attemps = user.getFailedAttempts() != null ? user
						.getFailedAttempts() : 0;
				user.setFailedAttempts(attemps + 1);
				em.persist(user);
				throw new AuthenticationException();
			} else {
				user.setFailedAttempts(0);
				em.persist(user);
			}
		} catch (Exception e) {
			logger.error("Problem while authenticating user", e);
			throw new AuthenticationException();
		}
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#reset(java.lang.String)
	 */
	public String reset(String username) throws UserException, AuthenticationException {
		if (username == null || username.trim().length() == 0) {
			throw new IllegalArgumentException(
					"username should never be null or empty");
		}
		@SuppressWarnings("unchecked")
		User user = get(username);
		if (user == null) {
			throw new AuthenticationException();
		}
		// Setup a confirmation string, set the user to right state and send
		// the confirmation email
		String confirmationString = Long.toString(random.nextLong()) + Long.toString(random.nextLong());
		try {
			if (StringUtils.isNotBlank(user.getEmail())) {
				user.setConfirmationHash(salt(user.getPasswordSalt(),
						confirmationString));
				em.persist(user);
			} else {
				throw new UserException("No available email, see administrator");
			}
		} catch (Exception e) {
			// Failed log it
			logger.error("Failed to reset password, problem was", e);
			throw new UserException(
					"Failed to reset user password, see administrator");
		}
		
		return confirmationString;
	}

	/* (non-Javadoc)
	 * @see org.mitre.itflogin.impl.UserManager#salt(int, java.lang.String)
	 */
	public String salt(int saltValue, String password)
			throws NoSuchAlgorithmException, IOException {
		if (password == null || password.trim().length() == 0) {
			throw new IllegalArgumentException(
					"password should never be null or empty");
		}
		byte[] pdata = password.getBytes("UTF8");
		byte[] cdata = new byte[4];
		cdata[0] = (byte) (saltValue & 0x000000FF);
		cdata[1] = (byte) ((saltValue & 0x0000FF00) >> 8);
		cdata[2] = (byte) ((saltValue & 0x00FF0000) >> 16);
		cdata[3] = (byte) ((saltValue & 0xFF000000) >> 24);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(cdata);
		byte thedigest[] = digest.digest(pdata);
		StringBuilder rval = new StringBuilder(thedigest.length * 2);
		for (int i = 0; i < thedigest.length; i++) {
			String part = String.format("%02x", thedigest[i]);
			rval.append(part);
		}
		return rval.toString();
	}
		
	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.UserInfoRepository#getByUserId(java.lang.String)
	 */
	public UserInfo getByUserId(String userId) {
		if (userId == null || userId.trim().length() == 0) {
			throw new IllegalArgumentException(
					"userId should never be null or empty");
		}
		User user = get(userId);
		if (user != null) {
			return userToUserInfo(user);
		} else {
			return null;
		}
	}

	/**
	 * Convert a user object to a userInfo object
	 * 
	 * @param user
	 * @return
	 */
	private UserInfo userToUserInfo(User user) {
		Collection<UserAttribute> attrs = user.getAttributes();
		Map<String, String> amap = attributesToMap(attrs);
		UserInfo info = new UserInfo();
		info.setEmail(user.getEmail());
		info.setFamilyName(amap.get(StandardAttributes.LAST_NAME.name()));
		info.setGender(amap.get(StandardAttributes.GENDER.name()));
		info.setGivenName(amap.get(StandardAttributes.FIRST_NAME.name()));
		info.setLocale(amap.get(StandardAttributes.LOCALE.name()));
		info.setMiddleName(amap.get(StandardAttributes.MIDDLE_NAME.name()));
		info.setName(amap.get(StandardAttributes.NAME.name()));
		info.setNickname(amap.get(StandardAttributes.NICKNAME.name()));
		info.setPhoneNumber(amap.get(StandardAttributes.PHONE_NUMBER.name()));
		info.setPicture(amap.get(StandardAttributes.PICTURE.name()));
		info.setProfile(amap.get(StandardAttributes.PROFILE.name()));
		info.setUpdatedTime(amap.get(StandardAttributes.UPDATED_TIME.name()));
		info.setUserId(user.getId().toString());
		info.setVerified(user.getEmailConfirmed());
		info.setWebsite(amap.get(StandardAttributes.WEBSITE.name()));
		info.setZoneinfo(amap.get(StandardAttributes.ZONEINFO.name()));
		String street = amap.get(StandardAttributes.STREET_ADDRESS.name());
		String faddr = amap.get(StandardAttributes.FORMATTED_ADDRESS.name());
		String locality = amap.get(StandardAttributes.LOCALITY.name());
		String region = amap.get(StandardAttributes.REGION.name());
		String postal = amap.get(StandardAttributes.POSTAL_CODE.name());
		String country = amap.get(StandardAttributes.COUNTRY.name());
		if (StringUtils.isNotBlank(street) || StringUtils.isNotBlank(faddr)
				|| StringUtils.isNotBlank(locality)
				|| StringUtils.isNotBlank(region)
				|| StringUtils.isNotBlank(country)
				|| StringUtils.isNotBlank(postal)) {
			Address addr = new Address();
			addr.setFormatted(faddr);
			addr.setStreetAddress(street);
			addr.setLocality(locality);
			addr.setRegion(region);
			addr.setCountry(country);
			addr.setPostalCode(postal);
			info.setAddress(addr);
		}
		return info;
	}

	/**
	 * Convert attributes into a hash map
	 * @param attrs
	 * @return
	 */
	private Map<String, String> attributesToMap(Collection<UserAttribute> attrs) {
		Map<String,String> rval = new HashMap<String, String>();
		for(UserAttribute attr : attrs) {
			if (attr.getType() != UserAttribute.NORMAL_TYPE) continue;
			rval.put(attr.getName(), attr.getValue());
		}
		return rval;
	}

	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.UserInfoRepository#save(org.mitre.openid.connect.model.UserInfo)
	 */
	public UserInfo save(UserInfo userInfo) {
		if (userInfo == null) {
			throw new IllegalArgumentException(
					"userInfo should never be null");
		}
		String userId = userInfo.getUserId();
		if (userId == null || userId.trim().length() == 0) {
			throw new IllegalArgumentException(
					"userId should never be null or empty");
		}
		User user = get(userId);
		if (user == null) {
			user = new User();
			user.setUsername(userId);
			try {
				setUserPassword(userId, Long.toHexString(RandomUtils.nextLong()),user);
			} catch (UserException e) {
				logger.error("Problem setting up user password in userinfo save", e);
			}
		}
		/**
		 * Set user information from userInfo
		 */
		if (StringUtils.isNotBlank(userInfo.getEmail()))
			user.setEmail(userInfo.getEmail());
		addUserAttribute(user, StandardAttributes.LAST_NAME, userInfo.getFamilyName());
		addUserAttribute(user, StandardAttributes.FIRST_NAME, userInfo.getGivenName());
		addUserAttribute(user, StandardAttributes.LOCALE, userInfo.getLocale());
		addUserAttribute(user, StandardAttributes.MIDDLE_NAME, userInfo.getMiddleName());
		addUserAttribute(user, StandardAttributes.NAME, userInfo.getName());
		addUserAttribute(user, StandardAttributes.NICKNAME, userInfo.getNickname());
		addUserAttribute(user, StandardAttributes.PHONE_NUMBER, userInfo.getPhoneNumber());
		addUserAttribute(user, StandardAttributes.PICTURE, userInfo.getPicture());
		addUserAttribute(user, StandardAttributes.PROFILE, userInfo.getProfile());
		addUserAttribute(user, StandardAttributes.WEBSITE, userInfo.getWebsite());
		addUserAttribute(user, StandardAttributes.ZONEINFO, userInfo.getZoneinfo());
		addUserAttribute(user, StandardAttributes.GENDER, userInfo.getGender());
		if (userInfo.getAddress() != null) {
			Address addr = userInfo.getAddress();
			addUserAttribute(user, StandardAttributes.FORMATTED_ADDRESS, addr.getFormatted());
			addUserAttribute(user, StandardAttributes.STREET_ADDRESS, addr.getStreetAddress());
			addUserAttribute(user, StandardAttributes.LOCALITY, addr.getLocality());
			addUserAttribute(user, StandardAttributes.REGION, addr.getRegion());
			addUserAttribute(user, StandardAttributes.POSTAL_CODE, addr.getPostalCode());
			addUserAttribute(user, StandardAttributes.COUNTRY, addr.getCountry());
		}
		user.setEmailConfirmed(userInfo.getVerified());
		save(user);
		
		return userInfo;
	}
	
	/**
	 * Add the named attribute to the user if not blank/null
	 * @param user
	 * @param attr
	 * @param value
	 */
	private void addUserAttribute(User user, StandardAttributes name, String value) {
		if (StringUtils.isNotBlank(value)) {
			if (user.getAttributes() == null) {
				user.setAttributes(new HashSet<UserAttribute>());
			}
			user.getAttributes().add(new UserAttribute(name, value));
		}
	}

	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.UserInfoRepository#remove(org.mitre.openid.connect.model.UserInfo)
	 */
	public void remove(UserInfo userInfo) {
		removeByUserId(userInfo.getUserId());
	}

	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.UserInfoRepository#removeByUserId(java.lang.String)
	 */
	public void removeByUserId(String userId) {
		if (userId == null || userId.trim().length() == 0) {
			throw new IllegalArgumentException(
					"userId should never be null or empty");
		}
		User u = get(userId);
		if (u != null) {
			em.remove(u); // Insures that all cascading, etc. is done
		}
	}

	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.repository.UserInfoRepository#getAll()
	 */
	public Collection<UserInfo> getAll() {
		TypedQuery<User> uq = (TypedQuery<User>) em.createQuery(
				"select u from User u");
		List<User> users = uq.getResultList();
		List<UserInfo> userInfos = new ArrayList<UserInfo>();
		for(User u : users) {
			userInfos.add(userToUserInfo(u));
		}
		return userInfos;
	}

	/**
	 * @return the passwordRule
	 */
	public IPasswordRule getPasswordRule() {
		return passwordRule;
	}

	/**
	 * @param passwordRule
	 *            the passwordRule to set
	 */
	public void setPasswordRule(IPasswordRule passwordRule) {
		this.passwordRule = passwordRule;
	}

	/**
	 * @return the mailer
	 */
	public MailSender getMailer() {
		return mailer;
	}

	/**
	 * @param mailer
	 *            the mailer to set
	 */
	public void setMailer(MailSender mailer) {
		this.mailer = mailer;
	}

	/**
	 * @return the attemptLimit
	 */
	public int getAttemptLimit() {
		return attemptLimit;
	}

	/**
	 * @param attemptLimit
	 *            the attemptLimit to set
	 */
	public void setAttemptLimit(int attemptLimit) {
		this.attemptLimit = attemptLimit;
	}

	public URL getBaseURL() {
		return base;
	}
	
	/**
	 * @return the base
	 */
	public String getBase() {
		return base.toExternalForm();
	}

	/**
	 * @param base the base to set
	 */
	public void setBase(String base) {
		try {
			this.base = new URL(base);
		} catch (MalformedURLException e) {
			logger.error("Problem parsing base url", e);
		}
	}

	/**
	 * @return the defaultAdminUserName
	 */
	public String getDefaultAdminUserName() {
		return defaultAdminUserName;
	}

	/**
	 * @param defaultAdminUserName the defaultAdminUserName to set
	 */
	public void setDefaultAdminUserName(String defaultAdminUserName) {
		this.defaultAdminUserName = defaultAdminUserName;
	}

	/**
	 * @return the defaultAdminUserEmail
	 */
	public String getDefaultAdminUserEmail() {
		return defaultAdminUserEmail;
	}

	/**
	 * @param defaultAdminUserEmail the defaultAdminUserEmail to set
	 */
	public void setDefaultAdminUserEmail(String defaultAdminUserEmail) {
		this.defaultAdminUserEmail = defaultAdminUserEmail;
	}

	/**
	 * @return the userValidity
	 */
	public IUserValidity getUserValidity() {
		return userValidity;
	}

	/**
	 * @param userValidity the userValidity to set
	 */
	public void setUserValidity(IUserValidity userValidity) {
		if (userValidity == null) {
			throw new IllegalArgumentException(
					"userValidity should never be null");
		}
		this.userValidity = userValidity;
	}
}