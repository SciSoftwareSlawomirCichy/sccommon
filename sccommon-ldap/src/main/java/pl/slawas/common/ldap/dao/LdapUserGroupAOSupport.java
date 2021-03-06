package pl.slawas.common.ldap.dao;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingException;

import org.apache.commons.lang.StringUtils;

import pl.slawas.common.ldap.api.Constants;
import pl.slawas.common.ldap.api.ILdapConnectionFactory;
import pl.slawas.common.ldap.api.ILdapContextFactory;
import pl.slawas.common.ldap.api.ILdapUserGroup;
import pl.slawas.common.ldap.api.ILdapUserGroupAO;
import pl.slawas.common.ldap.api.IUserAttribute;
import pl.slawas.common.ldap.provider.LdapConnectionFactoryBean;
import pl.slawas.common.ldap.provider.LdapResult;
import pl.slawas.common.ldap.provider.LdapValue;
import pl.slawas.common.ldap.provider.ProviderOptions;
import pl.slawas.common.ldap.utils.UserAttributeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * LdapUserGroupAOSupport
 *
 * @author Sławomir Cichy &lt;slawomir.cichy@ibpm.pro&gt;
 * @version $Revision: 1.1 $
 *
 * @param <G>
 */
public abstract class LdapUserGroupAOSupport<G extends ILdapUserGroup> extends LdapAOSupport<G>
		implements ILdapUserGroupAO<G> {

	private static final long serialVersionUID = 715770566979293240L;

	private static final Logger logger = LoggerFactory.getLogger(LdapUserGroupAOSupport.class.getName());

	private final transient ILdapConnectionFactory factory;

	private final transient ProviderOptions lo;

	/**
	 * Lista atrybutów entry wyznaczona na podstawie konfiguracji security
	 * provider'a wraz z atrybutami dodatkowymi, których dodanie jest możliwe w
	 * sposób programistyczny - np. atrybuty związanie z datą ostatniej modyfikacji,
	 * czy też z policy haseł.
	 */
	private final String[] attrs;
	/**
	 * Pomocnicza lista atrybutów do weryfikacji czy dany atrybut został pobrany
	 * razem z entry
	 */
	private final Set<String> attrSet;
	/**
	 * lista atrybutów dodatkowych entry, których dodanie jest możliwe w sposób
	 * programistyczny - np. atrybuty związanie z datą ostatniej modyfikacji, czy
	 * też z policy haseł. Lista definiowana przy użyciu odpowiedniego konstruktora.
	 */
	private final String[] additionalAttrs;

	/**
	 * Podstawowy konstruktor instancji obiektu dostępu do grup LDAP
	 * 
	 * @param ldapOptions
	 *            parametr niezbędne do nawiązania połączenia do LDAP
	 * @param organizationalUnitName
	 *            nazwa jednostki organizacyjnej, w której drzewie znajdują się
	 *            entry użytkowników.
	 */
	public LdapUserGroupAOSupport(ProviderOptions ldapOptions, String organizationalUnitName) {
		this(ldapOptions, organizationalUnitName, null);
	}

	/**
	 * Podstawowy konstruktor instancji obiektu dostępu do grup LDAP
	 * 
	 * @param ldapOptions
	 *            parametr niezbędne do nawiązania połączenia do LDAP
	 * @param organizationalUnitName
	 *            nazwa jednostki organizacyjnej, w której drzewie znajdują się
	 *            entry użytkowników.
	 * @param additionalAttrs
	 *            dodatkowe atrybuty pobierane wraz z entry grupy.
	 */
	public LdapUserGroupAOSupport(ProviderOptions ldapOptions, String organizationalUnitName,
			String[] additionalAttrs) {
		this.factory = new LdapConnectionFactoryBean(ldapOptions, organizationalUnitName, this.getClass());
		lo = factory.getLdapOptions();

		// get list of groups attributes
		Set<String> vAttr = new HashSet<>();
		vAttr.add(Constants.DEFAULT_EMAIL_ATTR_NAME);
		vAttr.add(lo.getGroupNameAttribute());
		vAttr.add(lo.getGroupDisplayNameAttribute());
		vAttr.add(lo.getGroupMemberAttribute());
		vAttr.add(lo.getGroup2GroupTokenAttribute());
		if (StringUtils.isNotBlank(lo.getGroupDNAttribute())) {
			vAttr.add(lo.getGroupDNAttribute());
		}
		if (StringUtils.isNotBlank(lo.getGroupDescriptionAttribute())) {
			vAttr.add(lo.getGroupDescriptionAttribute());
		}

		// copy the list into final array of attributes
		int attrSize = vAttr.size() + (additionalAttrs != null ? additionalAttrs.length : 0);
		attrs = new String[attrSize];
		attrSet = new HashSet<>(attrSize);
		/*
		 * wpierw przepisuję podstawowe informacje o atrybutach wynikające z
		 * konfiguracji
		 */
		Iterator<String> it = vAttr.iterator();
		int i = 0;
		StringBuilder traceOut = new StringBuilder();
		if (logger.isTraceEnabled()) {
			traceOut.append("\n---------- base attributes: ----------");
		}
		while (it.hasNext()) {
			attrs[i] = it.next();
			attrSet.add(attrs[i]);
			if (logger.isTraceEnabled()) {
				traceOut.append("\n\t").append(i).append(": ").append(attrs[i]);
			}
			i++;
		}
		/* teraz dopiszę dodatkowe */
		this.additionalAttrs = additionalAttrs;
		if (additionalAttrs != null && additionalAttrs.length != 0) {
			if (logger.isTraceEnabled()) {
				traceOut.append("\n---------- additional attributes: ----------");
			}
			for (String addAttr : additionalAttrs) {
				attrs[i] = addAttr;
				attrSet.add(attrs[i]);
				if (logger.isTraceEnabled()) {
					traceOut.append("\n\t").append(i).append(": ").append(attrs[i]);
				}
				i++;
			}
		}
		if (logger.isTraceEnabled()) {
			logger.trace("LdapUserGroupAOSupport: {}", traceOut.toString());
		}
	}

	/**
	 * Przekształcenie wyniku LDAP do obiektu grupy
	 * 
	 * @param result
	 *            wynik LDAP
	 * @return obiekt grupy
	 * @throws NamingException
	 */
	protected G transform2UserGroup(LdapResult result) throws NamingException {

		if (result == null)
			return null;

		String name = LdapAOHelper.readValue(result, lo.getGroupNameAttribute());
		G single = createNewInstance(name);
		// main variables
		String displayName = LdapAOHelper.readValue(result, lo.getGroupDisplayNameAttribute());
		if (StringUtils.isNotBlank(displayName) && !Constants.NULL_STRING.equals(displayName)) {
			single.setDisplayName(displayName);
		}
		String description = LdapAOHelper.readValue(result, lo.getGroupDescriptionAttribute());
		if (StringUtils.isNotBlank(description) && !Constants.NULL_STRING.equals(description)) {
			single.setDescription(description);
		}
		String email = LdapAOHelper.readValue(result, Constants.DEFAULT_EMAIL_ATTR_NAME);
		if (StringUtils.isNotBlank(email) && !Constants.NULL_STRING.equals(email)) {
			single.setEmail(email);
		}

		/* ustawiamy dodatkowe atrybuty grupy */
		if (this.additionalAttrs != null && this.additionalAttrs.length != 0) {
			List<IUserAttribute> attributes = UserAttributeUtils.createAtributeList(this.lo, logger,
					this.additionalAttrs, result, single, this.attrSet);
			single.setAttributes(attributes);
		}

		String groupDN = null;
		if (lo.getGroupDNAttribute() != null) {
			groupDN = LdapAOHelper.readValue(result, lo.getGroupDNAttribute());
			if (StringUtils.isBlank(groupDN)) {
				throw new NamingException("Can't follow referal for group: " + name);
			}
		} else {
			String rname = result.getName();
			if (result.isRelative()) {
				groupDN = rname + "," + getFactory().getDynamicCtx();
			} else {
				throw new NamingException("Can't follow referal for authentication: " + name);
			}
		}
		logger.trace("groupDN={}", groupDN);
		single.setDn(groupDN);
		single.setStructureUnit(lo.checkStructureUnit(single.getDn()));

		/* ustawianie namiarów na rodzica */
		List<LdapValue> memberOfListResult = result.get(lo.getGroup2GroupTokenAttribute());
		String mainMemberOf = null;
		if (memberOfListResult != null) {
			/** Jest jakiś wynik dla atrybutu 'memberOf' - start */
			Set<String> otherMembersOf = new HashSet<>();

			if (lo.getGroup2GroupTokenAttributeIsDN()) {
				/** powiązania pomiędzy grupami są zdefiniowane jako DN - start */
				mainMemberOf = loadMembersOfByDN(single, memberOfListResult, otherMembersOf);
				/** powiązania pomiędzy grupami są zdefiniowane jako DN - koniec */
			} else {
				/**
				 * powiązania pomiędzy grupami są zdefiniowane wartości atrybutu innej grupy -
				 * start
				 */
				mainMemberOf = loadMembersOfByAttr(single, memberOfListResult, otherMembersOf);
				/**
				 * powiązania pomiędzy grupami są zdefiniowane wartości atrybutu innej grupy -
				 * koniec
				 */
			}
			/* ustawiam końcowe ustalenia co do rodziców grupy */
			if (StringUtils.isNotBlank(mainMemberOf)) {
				/*
				 * jeżeli została ustalony rodzic główny to go usuwam z listy wszystkich
				 * rodziców.
				 */
				otherMembersOf.remove(mainMemberOf);
			} else if (logger.isWarnEnabled()) {
				StringBuilder sb = new StringBuilder();
				if (!otherMembersOf.isEmpty()) {
					int i = 0;
					for (String memberOf : otherMembersOf) {
						if (i != 0) {
							sb.append(", ");
						}
						sb.append(memberOf);
						i++;
					}
				}
				String extMsg = sb.toString();
				logger.warn("Nie udało się ustalić rodzica głównego dla grupy {}{}", new Object[] { single.getName(),
						(StringUtils.isNotBlank(extMsg) ? " pośród znanych rodziców: " + extMsg : StringUtils.EMPTY) });
			}
			single.setMainMemberOf(mainMemberOf);
			single.setOtherMembersOf(otherMembersOf);
			/** Jest jakiś wynik dla atrybutu 'memberOf' - koniec */
		}

		Set<String> members = new HashSet<>();
		List<LdapValue> membersListResult = result.get(lo.getGroupMemberAttribute());

		for (LdapValue memberResult : membersListResult) {
			if (memberResult.getValue() != null) {
				String member = (String) memberResult.getValue();
				if (!Constants.NULL_STRING.equalsIgnoreCase(member) && StringUtils.isNotBlank(member)) {
					members.add(member);
				}
			}
		}

		single.setMembers(members);

		return single;
	}

	/**
	 * Ładowanie rodziców grupy na podstawie zdefiniowanych atrybutów.
	 * 
	 * @param single
	 *            obiekt analizowanej grupy
	 * @param memberOfListResult
	 *            lista zwróconych wartości atrybutu 'memebreOf' analizowanej grupy.
	 * @param otherMembersOf
	 *            budowana lista rodziców.
	 * @return wskazanie na rodzica głównego (może być puste, jeżeli grupa rodzica
	 *         głównego nie zostanie zidentyfikowana).
	 */
	protected String loadMembersOfByAttr(G single, List<LdapValue> memberOfListResult, Set<String> otherMembersOf) {
		/*
		 * Może być problem, gdy grupa jest członkiem wielu grup. Aby rozwiązać ten
		 * problem bierzemy pierwszą z brzegu aby ustawić mainMemberOf.
		 */
		String mainMemberOf = null;
		if (memberOfListResult.size() > 1) {
			logger.warn(
					"Może być problem, gdy grupa {} jest członkiem wielu grup. Aby rozwiązać ten problem bierzemy pierwszą z brzegu aby ustawić mainMemberOf.",
					single.getName());
		}
		int ii = 0;
		for (LdapValue memberResult : memberOfListResult) {
			String currMember = (String) memberResult.getValue();
			if (!Constants.NULL_STRING.equalsIgnoreCase(currMember) && StringUtils.isNotBlank(currMember)) {
				otherMembersOf.add(currMember);
				if (ii == 0) {
					logger.trace("Mam kandydata na rodzica głównego! group currMember = {}", currMember);
					mainMemberOf = currMember;
				}
				ii++;
			}
		}
		return mainMemberOf;
	}

	/**
	 * Ładowanie rodziców grupy na podstawie DN'ów
	 * 
	 * @param single
	 *            obiekt analizowanej grupy
	 * @param memberOfListResult
	 *            lista zwróconych wartości atrybutu 'memebreOf' analizowanej grupy.
	 * @param otherMembersOf
	 *            budowana lista rodziców.
	 * @return wskazanie na rodzica głównego (może być puste, jeżeli grupa rodzica
	 *         głównego nie zostanie zidentyfikowana).
	 */
	protected String loadMembersOfByDN(G single, List<LdapValue> memberOfListResult, Set<String> otherMembersOf) {
		/* waga bliskości kandydata na rodzica głównego */
		int weight = -1;
		String mainMemberOf = null;
		String[] singleDNElements = single.getDn().split("\\,");
		if (singleDNElements.length > 1) {
			/** właściwa DN grupy - start */
			for (LdapValue memberResult : memberOfListResult) {
				/** pętla po rodzicach - start */
				String currMemberOfDN = (String) memberResult.getValue();
				logger.trace("group currMemberOfDN = {}", currMemberOfDN);
				String[] currMemberOfDNElements = currMemberOfDN.split("\\,");
				if (currMemberOfDNElements.length > 1) {
					/** właściwa długość obu DN - start */
					/* Wszystko OK - dodaję rodzica do listy rodziców */
					otherMembersOf.add(currMemberOfDN);
					/*
					 * Może być problem, gdy grupa jest członkiem wielu grup. Aby rozwiązać ten
					 * problem robimy założenie, że właściwa grupa (dla której budowane jest drzewo
					 * hierarchii, znajduje się tej samej gałęzi.Trzeba teraz znaleźć odpowiednią
					 * grupę, przypisać do pola mainMemberOf. Zakładam, że główny rodzic musi być w
					 * tym samym drzewie!!!
					 */
					/*
					 * Zatem może tak (pamiętaj, że to jest pętla!!! - raz tylko identyfikujemy
					 * głównego rodzica) biorąc pod uwagę wagi już zidentyfikowanych - wybieramy
					 * najbliższego.
					 */
					if (currMemberOfDNElements.length > singleDNElements.length) {
						/*
						 * Długość DN rodzica jest dłuższa od DN grupy! to oznacza, że rodzic jest albo
						 * w zupełnie innym drzewie, albo w podgałęzi drzewa w którym znajduje się
						 * grupa. Można by było zbadać tę sytuację (co jest prawdą)...
						 */
						boolean isInSubTree = false;
						/*
						 * Pierwszy to CN, więc porównam tylko pozostałe elementy by stwierdzić czy
						 * rodzic jest w tym samym drzewie.
						 */
						for (int ii = 0; ii < singleDNElements.length - 1; ii++) {
							int singleDNElementsIndx = singleDNElements.length - 1 - ii;
							int currMemberOfDNElementsIndx = currMemberOfDNElements.length - 1 - ii;
							if (singleDNElementsIndx < 0 || currMemberOfDNElementsIndx < 0) {
								break;
							}
							isInSubTree = singleDNElements[singleDNElementsIndx]
									.equalsIgnoreCase(currMemberOfDNElements[currMemberOfDNElementsIndx]);
							if (logger.isTraceEnabled()) {
								logger.trace("check[{}] isMainMemberOfInSubTree = {} ('{}'=='{}'",
										new Object[] { ii, isInSubTree, singleDNElements[singleDNElementsIndx],
												currMemberOfDNElements[currMemberOfDNElementsIndx] });
							}
						}
						if (logger.isWarnEnabled() && isInSubTree) {
							logger.warn(
									"Grupa nadrzędna, rodzic, jest zdefiniowany w poddrzewie grupy {} (DN grupy: {}, DN rodzica: {}). "
											+ "Sugeruje to nieprawidłową organizację danych w repozytorium LDAP.",
									new Object[] { single.getName(), single.getDn(), currMemberOfDN });
						}
					} else {
						/*
						 * Długość DN rodzica jest krótsza lub równa DN grupy! To oznacza, że rodzic
						 * jest dobrym kandydatem, by potraktować go poważnie. Badam czy są w tym samym
						 * drzewie...
						 */
						boolean isInSameTree = false;
						/*
						 * potrzebuje poziomu zagnieżdżenia rodzica by zweryfikować wagę bliskości z
						 * grupą
						 */
						int depth = 0;
						/*
						 * Pierwszy to CN, więc porównam tylko pozostałe elementy by stwierdzić czy
						 * rodzic jest w tym samym drzewie.
						 */
						for (int ii = 0; ii < currMemberOfDNElements.length - 1; ii++) {
							isInSameTree = singleDNElements[singleDNElements.length - 1 - ii]
									.equalsIgnoreCase(currMemberOfDNElements[currMemberOfDNElements.length - 1 - ii]);
							if (logger.isTraceEnabled()) {
								logger.trace("check[{}] isMainMemberOf = {} ('{}'=='{}'",
										new Object[] { ii, isInSameTree,
												singleDNElements[singleDNElements.length - 1 - ii],
												currMemberOfDNElements[currMemberOfDNElements.length - 1 - ii] });
							}
							depth++;
						}
						if (isInSameTree && weight < depth) {
							weight = depth;
							mainMemberOf = currMemberOfDN;
							if (logger.isDebugEnabled()) {
								logger.debug(
										"Znalazłem kandydata na rodzica głównego! group mainMemberOf = {}; waga = {}",
										new Object[] { currMemberOfDN, weight });
							}

						}
					}
					/** właściwa długość obu DN - koniec */
				} else {
					logger.warn("Niewłaściwa długość DN rodzica {}. "
							+ "Sugeruje to nieprawidłową organizację danych w repozytorium LDAP lub złą konfigurację mechanizmu synchronizacji danych.",
							new Object[] { currMemberOfDN });
				}
				/** pętla po rodzicach - koniec */
			}
			/** właściwa DN grupy - koniec */
		} else {
			logger.warn("Niewłaściwa długość DN grupy {} (DN = {}). "
					+ "Sugeruje to nieprawidłową organizację danych w repozytorium LDAP lub złą konfigurację mechanizmu synchronizacji danych.",
					new Object[] { single.getName(), single.getDn() });
		}
		return mainMemberOf;
	}

	/**
	 * Wyszukiwanie grupy po jej DN.
	 */
	public G load(Object id) throws NamingException {

		String searchFilter = "(" + lo.getRoleAttributeID() + "=" + (String) id + ")";
		logger.debug("--> load: Sarch Filter: {}", searchFilter);
		LdapResult result = getFactory().uniqueEntrySearch(attrs, searchFilter);
		if (result != null) {
			return transform2UserGroup(result);
		}
		return null;
	}

	public G loadBySearchFilter(String searchFilter) throws NamingException {
		logger.debug("--> loadBySearchFilter: Sarch Filter: {}", searchFilter);
		LdapResult result = getFactory().uniqueEntrySearch(attrs, searchFilter);
		if (result != null) {
			G user = transform2UserGroup(result);
			if (user != null && logger.isDebugEnabled()) {
				logger.debug("Pobralem z LDAP: {}", user.toString());
			}
			return user;
		}
		return null;
	}

	public List<G> loadAll() throws NamingException {
		String searchFilter = lo.getGroupsFilter();
		logger.debug("--> loadAll: Sarch Filter: {}", searchFilter);
		return loadByFilter(searchFilter);
	}

	public G loadPrimaryUserGroup(String[] userArgs) throws NamingException {
		String searchFilter = lo.getUserPrimaryGroupFilter(userArgs);
		logger.debug("--> loadPrimaryUserGroup: Sarch Filter: {}", searchFilter);
		LdapResult result = getFactory().uniqueEntrySearch(attrs, searchFilter);
		if (result != null) {
			return transform2UserGroup(result);
		}
		return null;
	}

	public List<G> loadUserGroups(String[] userArgs) throws NamingException {
		String searchFilter = lo.getRoleFilter(userArgs);
		logger.debug("--> loadUserGroups: Sarch Filter: {}", searchFilter);
		return loadByFilter(searchFilter);

	}

	public Map<String, G> loadAllAsMap() throws NamingException {
		Map<String, G> map = new HashMap<>();
		return prepareAllAsMap(lo.getGroupsFilter(), map, false);
	}

	public Map<String, G> mergeAllAsMap(Map<String, G> target) throws NamingException {
		return prepareAllAsMap(lo.getGroupsFilter(), target, true);
	}

	@Override
	protected Map<String, G> prepareAllAsMap(String searchFilter, Map<String, G> target, boolean checkOldElement)
			throws NamingException {

		logger.debug("--> prepareAllAsMap: Sarch Filter: {}", searchFilter);
		List<LdapResult> resultsList = getFactory().manyEntrySearch(attrs, searchFilter);
		if (!checkOldElement) {
			for (LdapResult result : resultsList) {
				G group = transform2UserGroup(result);
				if (group != null && group.getName() != null) {
					checkMembers(result, group);
					target.put(group.getName().toUpperCase(), group);
				}
			}
		} else {
			for (LdapResult result : resultsList) {
				G group = transform2UserGroup(result);
				if (group != null && group.getName() != null) {
					checkMembers(result, group);
					String key = group.getName().toUpperCase();
					G oldGroup = target.get(key);
					if (!group.equals(oldGroup)) {
						target.put(key, group);
					}
				}
			}
		}

		return target;
	}

	private void checkMembers(LdapResult result, G group) throws NamingException {
		if (group.getMembers() == null || group.getMembers().isEmpty()) {
			/* spróbuję pobrać użytkowników po range */
			try {
				String roleId = result.get(lo.getRoleAttributeID()).get(0).getString();
				String internalFilter = "(" + lo.getRoleAttributeID() + "=" + roleId + ")";
				String memeberAttrName = lo.getGroupMemberAttribute();
				if (logger.isDebugEnabled()) {
					logger.debug("--> checkMembers: try get range for '{}': roleId={}",
							new Object[] { memeberAttrName, internalFilter });
				}
				Set<String> members = new HashSet<String>();
				LdapResult membersRange = getFactory().uniqueEntrySearchWithRangeAttr(memeberAttrName, internalFilter);
				if (membersRange != null && membersRange.get(memeberAttrName) != null) {
					for (LdapValue memberResult : membersRange.get(memeberAttrName)) {
						if (memberResult.getValue() != null) {
							String member = (String) memberResult.getValue();
							if (!Constants.NULL_STRING.equalsIgnoreCase(member) && StringUtils.isNotBlank(member)) {
								members.add(member);
							}
						}
					}
				}
				group.setMembers(members);
				if (logger.isDebugEnabled()) {
					logger.debug("--> checkMembers: range execution result: roleId={}; group.members.size={}",
							new Object[] { memeberAttrName, group.getMembers().size() });
				}
			} catch (UnsupportedEncodingException e) {
				/* to się nie zdarzy! */
			}
		}
	}
	
	/**
	 * @return the LDAP context factory
	 */
	protected ILdapContextFactory getFactory() {
		return factory.getLdapContextFactory();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see pro.ibpm.ldap.dao._LdapAO#close()
	 */
	@Override
	public void close() {
		factory.close();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see pro.ibpm.ldap.dao._LdapAO#getOrganizationalUnitName()
	 */
	@Override
	public String getOrganizationalUnitName() {
		return this.factory.getOrganizationalUnitName();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see pro.ibpm.ldap.dao._LdapAOFactory#getLdapContextFactory()
	 */
	@Override
	public ILdapContextFactory getLdapContextFactory() {
		return this.factory.getLdapContextFactory();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see pro.ibpm.ldap.dao._LdapAOFactory#isInicjalized()
	 */
	@Override
	public boolean isInicjalized() {
		return this.factory.isInicjalized();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see pro.ibpm.ldap.dao._LdapAOFactory#isDefaultCtxDN()
	 */
	@Override
	public boolean isDefaultCtxDN() {
		return this.factory.isDefaultCtxDN();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see pro.ibpm.ldap.dao._LdapAOFactory#getLdapOptions()
	 */
	@Override
	public ProviderOptions getLdapOptions() {
		return this.factory.getLdapOptions();
	}

	public ProviderOptions getLo() {
		return lo;
	}

	public String[] getAttrs() {
		return attrs;
	}

	/**
	 * @return the {@link #additionalAttrs}
	 */
	public String[] getAdditionalAttrs() {
		return additionalAttrs;
	}

	/**
	 * @return the {@link #attrSet}
	 */
	public Set<String> getAttrSet() {
		return attrSet;
	}

	/* Overridden (non-Javadoc) */
	@Override
	public List<G> loadByFilter(String searchFilter) throws NamingException {
		List<G> list = new ArrayList<>();
		logger.debug("loadByFilter -> Sarch Filter: {}", searchFilter);
		List<LdapResult> resultsList = super.loadByFilter(getFactory(), attrs, searchFilter);
		for (LdapResult result : resultsList) {
			G group = transform2UserGroup(result);
			if (group != null) {
				checkMembers(result, group);
				list.add(group);
			}
		}
		return list;
	}

	/**
	 * @return the {@link #logger}
	 */
	@Override
	protected Logger getLog() {
		return logger;
	}

}
