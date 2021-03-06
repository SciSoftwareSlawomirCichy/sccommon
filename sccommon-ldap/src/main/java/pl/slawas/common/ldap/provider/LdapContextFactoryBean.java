/*
 * Slawas.pl Copyright &copy; 2011-2012 
 * http://slawas.pl 
 * All rights reserved.
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN
 * NO EVENT SHALL SŁAWOMIR CICHY BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package pl.slawas.common.ldap.provider;

import java.io.IOException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

import org.apache.commons.lang.StringUtils;

import pl.slawas.common.ldap.api.Constants;
import pl.slawas.common.ldap.api.ILdapAttribute;
import pl.slawas.common.ldap.api.ILdapContextFactory;
import pl.slawas.common.ldap.api.ILdapEntry4Changes;
import pl.slawas.common.ldap.dao.LdapAOHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LdapContextFactoryBean - główna implementacja kontekstu LDAP/AD z metodami
 * wspierającymi wyszukiwanie entry grup i użytkowników przechowywanych w
 * repozytorium.
 * 
 * @author Sławomir Cichy &lt;slawas@slawas.pl&gt;
 * @version $Revision: 1.1 $
 * 
 */
public class LdapContextFactoryBean implements ILdapContextFactory {

	private static final int RANGE_SIZE = 1000;

	/**
	 * Prefiks dodawany do nazwy jednostki organizacyjnej w celu zbudowania
	 * poprawnej nazwy gałęzi kontekstu LDAP
	 */
	public static final String OU_PREFIX = "ou=";

	protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

	private static final String LDAP_ATTRIBUTES_BINARY = "java.naming.ldap.attributes.binary";
	private static final String LDAP_DEFAULT_INITIAL_CONTEXT = "com.sun.jndi.ldap.LdapCtxFactory";
	private static final String LDAP_DEFAULT_SECURITY_AUTHENTICATION = "simple";

	protected LdapContext baseCtx = null;

	private final ProviderOptions ldapOptions;

	private final Integer pageSize;

	private String dynamicCtx;

	/**
	 * Konstruktor dający możliwość podłączenia się do dowolnego serwera LDAP/AD
	 * 
	 * @param ldapOptions
	 *            parametry inicjalizacyjne kontekstu LDAP, zdefiniowane w dostawcy
	 *            opcji.
	 * @param providerURL
	 *            adres serwera LDAP w formacie URL np. {@code ldap://dc.ad.pl:389/}
	 * @param baseCtxDN
	 *            podstawowe drzewo wyszukiwania entry
	 * @param bindDN
	 *            DN użytkownika nawiązującego połączenie
	 * @param bindCredential
	 *            hasło użytkownika nawiązującego połączenie (otwartym tekstem)
	 * @param pageSize
	 *            Rozmiar strony wyniku wyszukiwania w LDAP.
	 */
	public LdapContextFactoryBean(ProviderOptions ldapOptions, String providerURL, String baseCtxDN, String bindDN,
			String bindCredential, Integer pageSize) {
		/* to musi być tutaj (na początku) i nigdzie indziej).... */
		this.ldapOptions = ldapOptions;
		this.pageSize = pageSize;
		/* ustawianie URL'i kontekstu */
		String entryProviderURL = buildProviderUrl(null, providerURL, baseCtxDN);
		initContext(bindDN, bindCredential, entryProviderURL);

	}

	/**
	 * Konstruktor kontekstu LDAP utworzonego na podstawie parametrów zdefiniowanych
	 * w systemie.
	 * 
	 * @see ProviderOptions
	 * 
	 * @param ldapOptions
	 *            parametry inicjalizacyjne kontekstu LDAP, zdefiniowane w dostawcy
	 *            opcji.
	 * @param organizationalUnitName
	 *            Opcjonalny parametr. Nazwa jednostki organizacyjnej Jeżeli
	 *            zostanie zdefiniowany to: to do {@link ProviderOptions#baseCtxDN}
	 *            zostanie dodany prefix {@code OU=}
	 *            {@link #organizationalUnitName}{@code ,}. Powyższe reguły dotyczą
	 *            również wyszukiwania grup, to znaczy parametr będzie prefix'em dla
	 *            wartości {@link ProviderOptions#rolesCtxDN}
	 * @param isGroupContext
	 *            flaga mówiąca czy kontekst LDAP będzie służył pobieraniu grup.
	 */
	public LdapContextFactoryBean(ProviderOptions ldapOptions, String organizationalUnitName, boolean isGroupContext) {
		this.ldapOptions = ldapOptions;
		this.pageSize = this.ldapOptions.getLdapResultPageSize();
		/* przeczytanie parametrów konfiguracyjnych */
		String providerURL = ldapOptions.getProviderUrl();
		String bindDN = ldapOptions.getBindDN();
		String bindCredential = ldapOptions.getBindCredential();
		String baseCtxDN = null;
		if (isGroupContext) {
			if (!ldapOptions.isUserGroupOptionsAreDefinded()) {
				throw new IllegalArgumentException("Nie można inicjalizować kontekstu wyszukiania dla "
						+ "grup skoro opcje definiujące takie wyszukinie nie zostały zdefiniowane.");
			}
			baseCtxDN = ldapOptions.getRolesCtxDN();
		} else {
			baseCtxDN = ldapOptions.getBaseCtxDN();
		}
		/* ustawianie URL'i kontekstu */
		String entryProviderURL = buildProviderUrl(organizationalUnitName, providerURL, baseCtxDN);

		logger.debug("Tworzę nowy kontekst: entryProviderURL: {}", entryProviderURL);
		initContext(bindDN, bindCredential, entryProviderURL);

	}

	/**
	 * Inicjalizacja połączeń do LDAP.
	 * 
	 * @param bindDN
	 *            wartość parametru {@link Context#SECURITY_PRINCIPAL} - DN
	 *            użytkownika nawiązującego połączenie
	 * @param bindCredential
	 *            wartość parametru {@link Context#SECURITY_CREDENTIALS} - hasło
	 *            użytkownika nawiązującego połączenie (otwartym tekstem)
	 * @param entryProviderURL
	 *            wartość parametru {@link Context#PROVIDER_URL} dla wyszukiwania
	 *            entry użytkowników.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void initContext(String bindDN, String bindCredential, String entryProviderURL) {

		Hashtable env = new Hashtable();
		env.put(Context.INITIAL_CONTEXT_FACTORY, LDAP_DEFAULT_INITIAL_CONTEXT);
		// Specify the LDAP protocol version
		env.put(Context.SECURITY_AUTHENTICATION, LDAP_DEFAULT_SECURITY_AUTHENTICATION);
		env.put(Context.SECURITY_PRINCIPAL, bindDN);
		env.put(Context.SECURITY_CREDENTIALS, bindCredential);
		env.put(LDAP_ATTRIBUTES_BINARY, CustomBinaryField.CUSTOM_BINARY_FIELDS);
		try {
			env.put(Context.PROVIDER_URL, entryProviderURL);
			this.baseCtx = new InitialLdapContext(env, null);
		} catch (NamingException e) {
			logger.error("Inicjalize userCtx.", e);
		}
		logger.debug("Utworzylem kontekst (polaczenie) dla userProviderURL = {}", entryProviderURL);

	}

	/**
	 * Budowane parametru {@link Context#PROVIDER_URL} dla odpowiednich kontekstów
	 * wyszukiwania na podstawie zadanych argumentów.
	 * 
	 * @param organizationalUnitName
	 *            Opcjonalny parametr (może być {@code null}). Nazwa jednostki
	 *            organizacyjnej Jeżeli zostanie zdefiniowany to: to do
	 *            {@link #ctxDN} zostanie dodany prefix {@code OU=}
	 *            {@link #organizationalUnitName}{@code ,}
	 * @param providerURL
	 *            adres serwera LDAP w formacie URL np. {@code ldap://dc.ad.pl:389/}
	 * @param ctxDN
	 *            podstawowe drzewo wyszukiwania entry
	 * @return gotowa do użycia wartość parametru {@link Context#PROVIDER_URL}
	 */
	private String buildProviderUrl(String organizationalUnitName, String providerURL, String ctxDN) {

		if (StringUtils.isNotBlank(organizationalUnitName)
				&& !organizationalUnitName.equals(LdapAOHelper.DEFAULT_LDAP_CONTEXT_NAME)) {
			this.dynamicCtx = (new StringBuilder()).append(OU_PREFIX).append(organizationalUnitName).append(",")
					.append(ctxDN).toString();
		} else {
			this.dynamicCtx = ctxDN;
		}

		String[] providers = providerURL.split(" ");

		StringBuilder sb = new StringBuilder();
		for (String lProvider : providers) {
			if (StringUtils.isNotBlank(lProvider)) {
				sb.append(lProvider);
				/* takie 'lekkie' zabezpieczenie przed błędami */
				if (!providerURL.endsWith("/")) {
					sb.append("/");
				}
				sb.append(this.dynamicCtx).append(" ");
			}
		}
		String url = sb.toString();
		logger.debug("URL: {}", url);
		return url;
	}

	public void close() throws NamingException {
		if (this.baseCtx != null) {
			baseCtx.close();
		}
	}

	@SuppressWarnings("rawtypes")
	private LdapResult getResult(String[] attrs, SearchResult sr) throws NamingException {

		LdapResult searchResult = new LdapResult();
		searchResult.setName(sr.getName());
		searchResult.setRelative(sr.isRelative());

		Attributes resultAttrs = sr.getAttributes();
		for (String attr : attrs) {
			List<LdapValue> values = new ArrayList<>();
			Attribute resAttr = (Attribute) resultAttrs.get(attr);
			if (resAttr != null) {
				NamingEnumeration attrValues = resAttr.getAll();
				while (attrValues.hasMore()) {
					Object objValue = attrValues.next();
					if (objValue instanceof String) {
						String value = (String) objValue;
						values.add(new LdapValue(value));
						if (logger.isTraceEnabled()) {
							logger.trace("{}: {}", new Object[] { attr, value });
						}
					} else if (objValue instanceof byte[]) {
						byte[] value = (byte[]) objValue;
						values.add(new LdapValue(value, Types.BLOB));
					} else {
						values.add(new LdapValue("Unknown type"));
						logger.warn("{}: Unknown type {}", new Object[] { attr, objValue.getClass().getName() });
					}
				}
			} else {
				logger.trace("Attribute {} is null", attr);
				values.add(new LdapValue("null"));
			}
			searchResult.put(attr, values);
		}

		return searchResult;
	}

	/**
	 * Metoda przygotowana z myślą o odwołaniu się do niej z poziomu JavaSript w
	 * WLE. Tam nie można specjalnie operować na macierzach z obiektem
	 * {@link String} więc najłatwiej przenieść transformację do takiej macierzy po
	 * stronie implementacji właśnie tej metody. Metoda przeprowadza wyszukiwanie w
	 * drzewie użytkowników.
	 * 
	 * @param attrsList
	 *            {@link String} z listą atrybutów odseparowanych znakiem przecinka
	 *            np {@code "cn,businessCategory,c"} <font color="#DD0000">nie wolno
	 *            używać spacji</font>
	 * @param searchFilter
	 *            definicja filtru wyszukiwania wg wszelkich zasad filtrów
	 *            wyszukiwania LDAP np. {@code "(sAMAccountName=slawas)"}
	 * @return obiekt wyniku wyszukiwania
	 * @throws NamingException
	 */
	public LdapResult uniqueEntrySearch(String attrsList, String searchFilter) throws NamingException {
		String[] attrs = attrsList.split("\\,");
		return uniqueEntrySearch(attrs, searchFilter);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public LdapResult uniqueEntrySearch(String[] attrs, String searchFilter) throws NamingException {

		NamingEnumeration results = null;
		LdapResult searchResult = null;
		SearchControls controls = new SearchControls();
		controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		controls.setReturningAttributes(attrs);

		if (baseCtx != null) {
			try {
				if (pageSize != null && pageSize.intValue() > 0) {
					this.baseCtx.setRequestControls(
							new Control[] { new PagedResultsControl(pageSize, Control.NONCRITICAL) });
				}
			} catch (IOException e) {
				throw new NamingException(e.getMessage());
			}
			results = baseCtx.search("", searchFilter, controls);
			if (results != null) {
				if (results.hasMore()) {
					SearchResult sr = (SearchResult) results.next();
					searchResult = getResult(attrs, sr);
				}
				results.close();
			}
		}

		return searchResult;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public LdapResult uniqueEntrySearchWithRangeAttr(String attributeName, String searchFilter) throws NamingException {

		LdapResult searchResult = null;
		if (baseCtx != null) {
			boolean finallyFinished = false;
			int step = 0;
			List<LdapValue> values = new ArrayList<>();
			while (!finallyFinished) {
				int start = step * RANGE_SIZE;
				int finish = start + (RANGE_SIZE - 1);
				String range = start + "-" + finish;
				SearchControls controls = new SearchControls();
				controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
				String rangeAttributeName = attributeName + ";Range=" + range;
				controls.setReturningAttributes(new String[] { rangeAttributeName });
				NamingEnumeration<SearchResult> rangedEntries = baseCtx.search("", searchFilter, controls);
				if (rangedEntries.hasMore()) {
					SearchResult sr = (SearchResult) rangedEntries.next();
					Attributes resultAttrs = sr.getAttributes();
					NamingEnumeration<? extends Attribute> all = resultAttrs.getAll();
					boolean hasMore = all.hasMore();
					if (!hasMore) {
						finallyFinished = true;
						values.add(new LdapValue(Constants.NULL_STRING));
						if (logger.isTraceEnabled()) {
							logger.trace("-->uniqueEntrySearchWithRangeAttr: end because range does nit exists!");
						}
					}
					while (hasMore) {
						Attribute attribute = all.next();
						String attrName = attribute.getID();
						if (attrName.endsWith("*")) {
							rangeAttributeName = attrName;
							finallyFinished = true;
						}
						NamingEnumeration attrValues = attribute.getAll();
						while (attrValues.hasMore()) {
							Object objValue = attrValues.next();
							if (objValue instanceof String) {
								String value = (String) objValue;
								values.add(new LdapValue(value));
								logger.trace("{}: {}", new Object[] { rangeAttributeName, value });
							} else if (objValue instanceof byte[]) {
								byte[] value = (byte[]) objValue;
								values.add(new LdapValue(value, Types.BLOB));
							} else {
								values.add(new LdapValue("Unknown type"));
								logger.warn("{}: Unknown type {}",
										new Object[] { rangeAttributeName, objValue.getClass().getName() });
							}
						}
						hasMore = all.hasMore();
					}
				} else {
					finallyFinished = true;
				}
				rangedEntries.close();
				step++;
			}
			searchResult = new LdapResult();
			searchResult.put(attributeName, values);
		}
		return searchResult;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public List<LdapResult> manyEntrySearch(String[] attrs, String searchFilter) throws NamingException {

		NamingEnumeration results = null;
		List<LdapResult> searchResults = new ArrayList<>();

		SearchControls controls = new SearchControls();
		controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		controls.setReturningAttributes(attrs);

		if (baseCtx != null) {
			logger.debug("pageSize={}", pageSize);
			if (this.pageSize != null && this.pageSize.intValue() > 0) {
				loadPagedResult(baseCtx, attrs, searchFilter, searchResults);
			} else {
				results = baseCtx.search("", searchFilter, controls);
				if (results != null) {
					while (results.hasMore()) {
						SearchResult sr = (SearchResult) results.next();
						LdapResult searchResult = getResult(attrs, sr);
						searchResults.add(searchResult);
					}
					results.close();
				}
			}
		}

		return searchResults;
	}

	/**
	 * @param ctx
	 * @param attrs
	 * @param searchFilter
	 * @param searchResults
	 * @throws NamingException
	 */
	@SuppressWarnings("rawtypes")
	private void loadPagedResult(LdapContext ctx, String[] attrs, String searchFilter, List<LdapResult> searchResults)
			throws NamingException {
		NamingEnumeration results;
		try {
			byte[] cookie = null;
			int total;
			ctx.setRequestControls(new Control[] { new PagedResultsControl(pageSize, Control.NONCRITICAL) });
			do {
				/* perform the search */
				SearchControls controls = new SearchControls();
				controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
				controls.setReturningAttributes(attrs);
				if (logger.isDebugEnabled()) {
					logger.debug("--> loadPagedResult: searchFilter={}, ctx={}",
							new Object[] { searchFilter, ctx.getNameInNamespace() });
				}
				results = ctx.search("", searchFilter, controls);

				/* for each entry print out name + all attrs and values */
				if (results != null) {
					while (results.hasMore()) {
						SearchResult sr = (SearchResult) results.next();
						LdapResult searchResult = getResult(attrs, sr);
						searchResults.add(searchResult);
					}
					results.close();
				}

				// Examine the paged results control response
				Control[] pControls = ctx.getResponseControls();
				if (pControls != null) {
					for (int i = 0; i < pControls.length; i++) {
						if (pControls[i] instanceof PagedResultsResponseControl) {
							PagedResultsResponseControl prrc = (PagedResultsResponseControl) pControls[i];
							total = prrc.getResultSize();
							if (logger.isDebugEnabled()) {
								if (total != 0) {
									logger.debug("\n***************** END-OF-PAGE (total : {}) *****************",
											total);
								} else {
									logger.debug("\n***************** END-OF-PAGE (total : unknown) *****************");
								}
							}
							cookie = prrc.getCookie();
						}
					}
				} else if (logger.isDebugEnabled()) {
					logger.debug("No controls were sent from the server");
				}
				// Re-activate paged results
				ctx.setRequestControls(new Control[] { new PagedResultsControl(pageSize, cookie, Control.CRITICAL) });

			} while (cookie != null);
		} catch (IOException e) {
			throw new NamingException(e.getMessage());
		}
	}

	/**
	 * @return the ldapOptions
	 */
	public ProviderOptions getLdapOptions() {
		return ldapOptions;
	}

	/**
	 * @return the {@link #entryProviderURL}
	 */
	public String getDynamicCtx() {
		return dynamicCtx;
	}

	/* Overridden (non-Javadoc) */
	@Override
	public void addEntry(ILdapEntry4Changes newEntry, String entryNameAttr) throws NamingException {

		if (newEntry == null) {
			logger.warn("-->addEntry: New entry is empty- operation skipped.");
			return;
		}
		if (StringUtils.isBlank(newEntry.getDn())) {
			logger.warn("-->addEntry: New entry has empty DN - operation skipped.");
			return;
		}
		String nameAttr = entryNameAttr;
		if (StringUtils.isBlank(newEntry.getName())) {
			logger.warn("-->addEntry: New entry has empty {} - operation skipped.", nameAttr);
			return;
		}
		if (newEntry.getChangesMap() == null || newEntry.getChangesMap().isEmpty()) {
			logger.warn("-->addEntry: New entry has empty changes map - operation skipped.");
			return;
		}

		StringBuilder debugBuilder = new StringBuilder();
		/*
		 * weryfikacja DN'a użytkownika - dn musi być względny wobec ustanowionego
		 * kontekstu połączenia
		 */
		String entryDN = newEntry.getDn();
		if (!entryDN.toLowerCase().endsWith(getDynamicCtx().toLowerCase())) {
			throw new NamingException("You can't change entry outside " + getDynamicCtx() + "context.");
		}
		entryDN = entryDN.substring(0, entryDN.length() - getDynamicCtx().length() - 1);

		if (logger.isDebugEnabled()) {
			debugBuilder.append("dn: ").append(entryDN).append("\n");
		}
		Attributes entry = new BasicAttributes();

		entry.put(new BasicAttribute(nameAttr, newEntry.getName()));
		if (logger.isDebugEnabled()) {
			debugBuilder.append(nameAttr).append(": ").append(newEntry.getName()).append("\n");
		}
		Set<Entry<String, List<ILdapAttribute>>> entrySet = newEntry.getChangesMap().entrySet();
		for (Entry<String, List<ILdapAttribute>> lEntry : entrySet) {
			nameAttr = lEntry.getKey();
			List<ILdapAttribute> values = lEntry.getValue();
			if (values.isEmpty()) {
				logger.warn("-->addEntry: New attribute has empty {} - attribute skipped.", nameAttr);
				continue;
			}
			Attribute attr = new BasicAttribute(nameAttr);
			for (ILdapAttribute lAttr : values) {
				Object value = lAttr.getValue();
				attr.add(value);
				if (logger.isDebugEnabled()) {
					debugBuilder.append(nameAttr).append(": ").append(value).append("\n");
				}
			}
			entry.put(attr);
		}
		if (logger.isDebugEnabled()) {
			logger.debug("-->addEntry: New entry attributes in {}: \n{}",
					new Object[] { this.getDynamicCtx(), debugBuilder.toString() });
		}
		try {
			if (pageSize != null && pageSize.intValue() > 0) {
				this.baseCtx
						.setRequestControls(new Control[] { new PagedResultsControl(pageSize, Control.NONCRITICAL) });
			}
		} catch (IOException e) {
			throw new NamingException(e.getMessage());
		}
		this.baseCtx.createSubcontext(entryDN, entry);
	}

	/* Overridden (non-Javadoc) */
	@Override
	public void modifyEntry(ILdapEntry4Changes modifiedEntry, String entryNameAttr) throws NamingException {
		if (modifiedEntry == null) {
			logger.warn("-->modifyEntry: Modified entry is empty- operation skipped.");
			return;
		}
		if (StringUtils.isBlank(modifiedEntry.getDn())) {
			logger.warn("-->modifyEntry: Modified entry has empty DN - operation skipped.");
			return;
		}
		String nameAttr = entryNameAttr;
		if (StringUtils.isBlank(modifiedEntry.getName())) {
			logger.warn("-->modifyEntry: Modified entry has empty {} - operation skipped.", nameAttr);
			return;
		}
		if (modifiedEntry.getChangesMap() == null || modifiedEntry.getChangesMap().isEmpty()) {
			logger.warn("-->modifyEntry: Modified entry has empty changes map - operation skipped.");
			return;
		}

		StringBuilder debugBuilder = new StringBuilder();
		/*
		 * weryfikacja DN'a użytkownika - dn musi być względny wobec ustanowionego
		 * kontekstu połączenia
		 */
		String entryDN = modifiedEntry.getDn();
		if (!entryDN.toLowerCase().endsWith(getDynamicCtx().toLowerCase())) {
			throw new NamingException("You can't change entry outside " + getDynamicCtx() + "context.");
		}
		entryDN = entryDN.substring(0, entryDN.length() - getDynamicCtx().length() - 1);

		if (logger.isDebugEnabled()) {
			debugBuilder.append("dn: ").append(entryDN).append("\n");
		}

		List<ModificationItem> modifications = new ArrayList<>();
		Set<Entry<String, List<ILdapAttribute>>> entrySet = modifiedEntry.getChangesMap().entrySet();
		for (Entry<String, List<ILdapAttribute>> lEntry : entrySet) {
			nameAttr = lEntry.getKey();
			List<ILdapAttribute> values = lEntry.getValue();
			if (values.isEmpty()) {
				logger.warn("-->modifyEntry: Modified attribute has empty {} - attribute skipped.", nameAttr);
				continue;
			}

			for (ILdapAttribute lAttr : values) {
				Object oldValue = lAttr.getOldValue();
				Object value = lAttr.getValue();
				if (oldValue == null && value != null) {
					/* Dodajemy atrybut */
					modifications
							.add(new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute(nameAttr, value)));
				} else if (oldValue != null && value != null) {
					/* Modyfikujemy atrybut */
					if (lAttr.isMultiValue()) {
						modifications.add(new ModificationItem(DirContext.REMOVE_ATTRIBUTE,
								new BasicAttribute(nameAttr, oldValue)));
						modifications.add(
								new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute(nameAttr, value)));
					} else {
						modifications.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
								new BasicAttribute(nameAttr, value)));
					}
				} else {
					/* Usuwamy atrybut */
					modifications.add(
							new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(nameAttr, oldValue)));
				}

				if (logger.isDebugEnabled()) {
					debugBuilder.append(nameAttr).append(": ").append(value).append("\n");
				}
			}
		}
		logger.debug("-->modifyEntry: Modified entry attributes in {}: \n{}",
				new Object[] { this.getDynamicCtx(), debugBuilder.toString() });
		try {
			if (pageSize != null && pageSize.intValue() > 0) {
				this.baseCtx
						.setRequestControls(new Control[] { new PagedResultsControl(pageSize, Control.NONCRITICAL) });
			}
		} catch (IOException e) {
			throw new NamingException(e.getMessage());
		}
		this.baseCtx.modifyAttributes(entryDN, modifications.toArray(new ModificationItem[] {}));

	}

	/* Overridden (non-Javadoc) */
	@Override
	public void removeEntry(String entryDN) throws NamingException {
		if (StringUtils.isBlank(entryDN)) {
			logger.warn("-->modifyEntry: Modified entry has empty DN - operation skipped.");
			return;
		}
		/*
		 * weryfikacja DN'a użytkownika - dn musi być względny wobec ustanowionego
		 * kontekstu połączenia
		 */
		if (!entryDN.toLowerCase().endsWith(getDynamicCtx().toLowerCase())) {
			throw new NamingException("You can't change entry outside " + getDynamicCtx() + "context.");
		}
		entryDN = entryDN.substring(0, entryDN.length() - getDynamicCtx().length() - 1);
		try {
			if (pageSize != null && pageSize.intValue() > 0) {
				this.baseCtx
						.setRequestControls(new Control[] { new PagedResultsControl(pageSize, Control.NONCRITICAL) });
			}
		} catch (IOException e) {
			throw new NamingException(e.getMessage());
		}
		this.baseCtx.destroySubcontext(entryDN);
	}

}
