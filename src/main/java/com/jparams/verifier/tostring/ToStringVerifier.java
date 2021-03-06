package com.jparams.verifier.tostring;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.jparams.object.builder.Build;
import com.jparams.object.builder.BuildStrategy;
import com.jparams.object.builder.Configuration;
import com.jparams.object.builder.ObjectBuilder;
import com.jparams.object.builder.type.Type;
import com.jparams.verifier.tostring.error.ClassNameVerificationError;
import com.jparams.verifier.tostring.error.ErrorMessageGenerator;
import com.jparams.verifier.tostring.error.FieldValue;
import com.jparams.verifier.tostring.error.FieldValue.ErrorType;
import com.jparams.verifier.tostring.error.FieldValueVerificationError;
import com.jparams.verifier.tostring.error.HashCodeVerificationError;
import com.jparams.verifier.tostring.error.VerificationError;
import com.jparams.verifier.tostring.preset.Preset;

/**
 * {@link ToStringVerifier} can be used in unit tests to verify that the {@link Object#toString()} returns
 * the desired results.
 *
 * <p>
 * To get started, use {@code ToStringVerifier} as follows:
 * <p>
 * {@code ToStringVerifier.forClass(MyClass.class).verify();}
 */
public final class ToStringVerifier
{
    private static final int TEST_REPEAT_COUNT = 3;
    private static final Formatter<Object> DEFAULT_FORMATTER = String::valueOf;

    private final Configuration configuration;
    private final List<Class<?>> classes;
    private final Map<Class<?>, Formatter<Object>> formatterMap = new HashMap<>();
    private NameStyle nameStyle = null;
    private FieldFilter fieldFilter = null;
    private boolean inheritedFields = true;
    private boolean hashCode = false;
    private String nullValue = "null";
    private boolean failOnExcludedFields = false;
    private HashCodeProvider hashCodeProvider = new SystemIdentityHashCodeProvider();

    private ToStringVerifier(final Collection<Class<?>> classes)
    {
        this.configuration = Configuration.defaultConfiguration().withDefaultBuildStrategy(BuildStrategy.AUTO).withFailOnError(false).withFailOnWarning(false);
        this.classes = classes.stream().filter(ToStringVerifier::isTestableClass).collect(Collectors.toList());
        this.classes.forEach(clazz -> configuration.withBuildStrategy(Type.forClass(clazz), BuildStrategy.FIELD_INJECTION));

        if (this.classes.isEmpty())
        {
            throw new IllegalArgumentException("No classes found to test. A class under test cannot be null, abstract, an interface or an enum.");
        }
    }

    /**
     * Create a verifier for the given class
     *
     * @param clazz class to test
     * @return verifier
     */
    public static ToStringVerifier forClass(final Class<?> clazz)
    {
        assertNotNull(clazz);
        return new ToStringVerifier(Collections.singletonList(clazz));
    }

    /**
     * Create a verifier for the given classes
     *
     * @param classes classes to test
     * @return verifier
     */
    public static ToStringVerifier forClasses(final Class<?>... classes)
    {
        return forClasses(Arrays.asList(classes));
    }

    /**
     * Create a verifier for the given classes
     *
     * @param classes classes to test
     * @return verifier
     */
    public static ToStringVerifier forClasses(final Collection<Class<?>> classes)
    {
        return new ToStringVerifier(classes);
    }

    /**
     * Create a verifier for a given package
     *
     * @param packageName     package under test
     * @param scanRecursively true to scan all sub-packages
     * @return verifier
     */
    public static ToStringVerifier forPackage(final String packageName, final boolean scanRecursively)
    {
        return forPackage(packageName, scanRecursively, (clazz) -> true);
    }

    /**
     * Create a verifier for a given package
     *
     * @param packageName     package under test
     * @param scanRecursively true to scan all sub-packages
     * @param predicate       defines which classes will be tested
     * @return verifier
     */
    public static ToStringVerifier forPackage(final String packageName, final boolean scanRecursively, final Predicate<Class<?>> predicate)
    {
        final List<Class<?>> classes = PackageScanner.findClasses(packageName, scanRecursively)
                                                     .stream()
                                                     .filter(ToStringVerifier::isTestableClass)
                                                     .filter(predicate)
                                                     .collect(Collectors.toList());

        return new ToStringVerifier(classes);
    }

    /**
     * A number of presets come with this library. For pre-made presets, see {@link com.jparams.verifier.tostring.preset.Presets}. A preset
     * is a pre-defined configuration for the ToStringVerifier.
     *
     * @param preset preset
     * @return verifier
     */
    public ToStringVerifier withPreset(final Preset preset)
    {
        preset.apply(this);
        return this;
    }

    /**
     * If specified, this tester will assert that the {@link Object#toString()} output contains the name of the subject class in
     * the {@link NameStyle} specified.
     *
     * @param nameStyle style of the class name
     * @return verifier
     */
    public ToStringVerifier withClassName(final NameStyle nameStyle)
    {
        this.nameStyle = nameStyle;
        return this;
    }

    /**
     * If set to true, this tester will assert that the {@link Object#toString()} output contains the hash code as returned
     * by {@link Object#hashCode()}.
     *
     * @param hashCode check for inclusion of the hash code
     * @return verifier
     */
    public ToStringVerifier withHashCode(final boolean hashCode)
    {
        this.hashCode = hashCode;
        return this;
    }

    /**
     * This is enabled by default. Set this to false to ignore fields inherited from the parent class.
     *
     * @param inheritedFields true to test for fields inherited from the parent class
     * @return verifier
     */
    public ToStringVerifier withInheritedFields(final boolean inheritedFields)
    {
        this.inheritedFields = inheritedFields;
        return this;
    }

    /**
     * If specified, this tester will only assert that the given fields are present in the {@link Object#toString()} output. To
     * assert all fields are present, do not call this method. To test for all fields excluding some, call {@link #withIgnoredFields(Collection)}.
     *
     * @param fields field names to include in test
     * @return verifier
     */
    public ToStringVerifier withOnlyTheseFields(final String... fields)
    {
        final Set<String> fieldNamesSet = new HashSet<>(Arrays.asList(fields));
        return withOnlyTheseFields(fieldNamesSet);
    }

    /**
     * If specified, this tester will only assert that the given fields are present in the {@link Object#toString()} output. To
     * assert all fields are present, do not call this method. To test for all fields excluding some, call {@link #withIgnoredFields(Collection)}.
     *
     * @param fields field names to include in test
     * @return verifier
     */
    public ToStringVerifier withOnlyTheseFields(final Collection<String> fields)
    {
        assertNotNull(fields);
        this.checkFieldPredicate();
        this.fieldFilter = (subject, field) -> fields.contains(field.getName());
        return this;
    }

    /**
     * If specified, this tester will assert that all but the excluded fields are present in the {@link Object#toString()} output. To
     * assert only certain fields are present, do not call this method, instead use {@link #withOnlyTheseFields(Collection)}.
     *
     * @param fields field names to exclude in test
     * @return verifier
     */
    public ToStringVerifier withIgnoredFields(final String... fields)
    {
        return withIgnoredFields(new HashSet<>(Arrays.asList(fields)));
    }

    /**
     * If specified, this tester will assert that all but the excluded fields are present in the {@link Object#toString()} output. To
     * assert only certain fields are present, do not call this method, instead use {@link #withOnlyTheseFields(Collection)}.
     *
     * @param fields field names to exclude in test
     * @return verifier
     */
    public ToStringVerifier withIgnoredFields(final Collection<String> fields)
    {
        assertNotNull(fields);
        this.checkFieldPredicate();
        this.fieldFilter = (subject, field) -> !fields.contains(field.getName());
        return this;
    }

    /**
     * If specified, this tester will only assert that field names matching this regex pattern are present in the {@link Object#toString()} output.
     *
     * @param regex field name match regex
     * @return verifier
     */
    public ToStringVerifier withMatchingFields(final String regex)
    {
        this.checkFieldPredicate();
        this.fieldFilter = (subject, field) -> field.getName().matches(regex);
        return this;
    }

    /**
     * If specified, this tester will only assert that field matching this filter criteria are present in the {@link Object#toString()} output.
     *
     * @param fields filter criteria
     * @return verifier
     */
    public ToStringVerifier withMatchingFields(final FieldFilter fields)
    {
        assertNotNull(fields);
        this.fieldFilter = fields;
        return this;
    }

    /**
     * Define how the verifier should behave if the {@link Object#toString()} output contains a field that has been explicitly excluded by
     * calling {@link #withIgnoredFields(String...)} or implicitly excluded by calling {@link #withOnlyTheseFields(String...)} or
     * {@link #withMatchingFields(String)}. By default, the verifier will not test for the presence of the excluded fields and will not fail
     * if they exist in the {@link Object#toString()} output when they should not. You can change this behaviour by setting <code>failOnExcludedFields</code>
     * to true.
     *
     * @param failOnExcludedFields fail if ignored fields are found in the {@link Object#toString()} output
     * @return verifier
     */
    public ToStringVerifier withFailOnExcludedFields(final boolean failOnExcludedFields)
    {
        this.failOnExcludedFields = failOnExcludedFields;
        return this;
    }

    /**
     * Adds prefabricated values for instance fields of classes that ToStringVerifier cannot instantiate by itself.
     *
     * @param type        The class of the prefabricated values
     * @param prefabValue An instance of {@code S}.
     * @param <S>         The class of the prefabricated values.
     * @return verifier
     */
    public <S> ToStringVerifier withPrefabValue(final Class<S> type, final S prefabValue)
    {
        this.configuration.withPrefabValue(Type.forClass(type), prefabValue);
        return this;
    }

    /**
     * Define a value formatter for the given class type.
     *
     * @param clazz     class
     * @param formatter formatter
     * @param <S>       value type
     * @return verifier
     */
    public <S> ToStringVerifier withFormatter(final Class<S> clazz, final Formatter<S> formatter)
    {
        assertNotNull(clazz);
        assertNotNull(formatter);
        @SuppressWarnings("unchecked") final Formatter<Object> objectFormatter = (Formatter<Object>) formatter;
        this.formatterMap.put(clazz, objectFormatter);
        return this;
    }

    /**
     * The value to expect on the toString output when a field value is null. This defaults to <code>null</code>
     *
     * @param nullValue null value
     * @return verifier
     */
    public ToStringVerifier withNullValue(final String nullValue)
    {
        assertNotNull(nullValue);
        this.nullValue = nullValue;
        return this;
    }

    /**
     * With hash code provider
     *
     * @param hashCodeProvider hash code provider
     * @return verifier
     */
    public ToStringVerifier withHashCodeProvider(final HashCodeProvider hashCodeProvider)
    {
        assertNotNull(hashCodeProvider);
        this.hashCodeProvider = hashCodeProvider;
        return this;
    }

    /**
     * Perform verification
     *
     * @throws AssertionError if assertion conditions are not met
     */
    public void verify()
    {
        for (int i = 0; i < TEST_REPEAT_COUNT; i++) // Run the same test multiple times to ensure consistency in outcome
        {
            final String message = classes.stream()
                                          .filter(Objects::nonNull)
                                          .map(this::verify)
                                          .filter(Optional::isPresent)
                                          .map(Optional::get)
                                          .reduce((message1, message2) -> message1 + "\n\n---\n\n" + message2)
                                          .orElse(null);

            if (message != null)
            {
                throw new AssertionError("\n\n" + message);
            }
        }
    }

    private Optional<String> verify(final Class<?> clazz)
    {
        final Build<?> build = ObjectBuilder.withConfiguration(configuration).buildInstanceOf(clazz);
        final Object subject = build.get();

        if (subject == null)
        {
            throw new AssertionError("Failed to create instance of " + clazz + ". Failed with error:\n" + build.getErrors());
        }

        final String stringValue = subject.toString();

        if (stringValue == null)
        {
            throw new AssertionError("toString method returned a null string");
        }

        final List<VerificationError> verificationErrors = new ArrayList<>();

        if (nameStyle != null)
        {
            verifyClassName(stringValue, nameStyle.getName(clazz)).ifPresent(verificationErrors::add);
        }

        if (hashCode)
        {
            verifyHashCode(stringValue, hashCodeProvider.provide(subject)).ifPresent(verificationErrors::add);
        }

        final List<FieldValue> fieldValues = FieldsProvider.provide(clazz, inheritedFields)
                                                           .stream()
                                                           .map(field -> verifyField(subject, stringValue, field, fieldFilter == null || fieldFilter.matches(clazz, field)))
                                                           .filter(Optional::isPresent)
                                                           .map(Optional::get)
                                                           .collect(Collectors.toList());

        if (!fieldValues.isEmpty())
        {
            verificationErrors.add(new FieldValueVerificationError(fieldValues));
        }

        if (verificationErrors.isEmpty())
        {
            return Optional.empty();
        }

        return Optional.of(ErrorMessageGenerator.generateErrorMessage(clazz, stringValue, verificationErrors));
    }

    private Optional<VerificationError> verifyClassName(final String stringValue, final String className)
    {
        if (stringValue.startsWith(className))
        {
            return Optional.empty();
        }

        return Optional.of(new ClassNameVerificationError(className));
    }

    private Optional<VerificationError> verifyHashCode(final String stringValue, final int hashCode)
    {
        if (stringValue.contains(String.valueOf(hashCode)) || stringValue.contains(Integer.toHexString(hashCode)))
        {
            return Optional.empty();
        }

        return Optional.of(new HashCodeVerificationError(hashCode));
    }

    private Optional<FieldValue> verifyField(final Object subject, final String stringValue, final Field field, final boolean expected)
    {
        final List<String> values = getFieldValues(subject, field);
        final String valuePattern = values.stream().map(Pattern::quote).reduce((val1, val2) -> val1 + "(.{0,4}?)" + val2).orElse("");
        final String regex = String.format("(.*)%s(.{0,4}?)%s(.*)", Pattern.quote(field.getName()), valuePattern);
        final Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        final boolean found = pattern.matcher(stringValue).matches();

        // Fail if we expected to find this field but did not
        // Fail if we did not expect to find this field and we did, and failOnExcludedFields is set to true
        if ((expected && !found) || (!expected && found && failOnExcludedFields))
        {
            final FieldValue.ErrorType errorType = found ? ErrorType.UNEXPECTED : ErrorType.EXPECTED;
            final String value = values.stream().reduce((val1, val2) -> val1 + ", " + val2).orElse("");
            final FieldValue fieldValue = new FieldValue(field.getName(), value, errorType);
            return Optional.of(fieldValue);
        }

        return Optional.empty();
    }

    private List<String> getFieldValues(final Object subject, final Field field)
    {
        final Object value;

        try
        {
            value = field.get(subject);
        }
        catch (final IllegalAccessException e)
        {
            throw new InternalErrorException("Failed to access internals of test subject", e);
        }

        if (value == null)
        {
            return Collections.singletonList(nullValue);
        }

        if (Proxy.isProxyClass(value.getClass()))
        {
            return Collections.singletonList(value.toString());
        }

        if (value.getClass().isArray())
        {
            return Stream.of((Object[]) value).map(this::formatValue).collect(Collectors.toList());
        }

        if (value instanceof Collection)
        {
            return ((Collection<?>) value).stream()
                                          .map(this::formatValue)
                                          .collect(Collectors.toList());
        }

        if (value instanceof Map)
        {
            return ((Map<?, ?>) value).entrySet()
                                      .stream()
                                      .map(entry -> String.format("%s=%s", formatValue(entry.getKey()), formatValue(entry.getValue())))
                                      .collect(Collectors.toList());
        }

        return Stream.of(value)
                     .map(this::formatValue)
                     .collect(Collectors.toList());
    }

    private String formatValue(final Object value)
    {
        return value == null ? nullValue : formatterMap.getOrDefault(value.getClass(), DEFAULT_FORMATTER).format(value);
    }

    private void checkFieldPredicate()
    {
        if (fieldFilter != null)
        {
            throw new IllegalArgumentException("You can call either withOnlyTheseFields, withIgnoredFields or withMatchingFields, but not a combination of the three.");
        }
    }

    private static boolean isTestableClass(final Class<?> clazz)
    {
        return clazz != null && !clazz.isEnum() && !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
    }

    private static void assertNotNull(final Object value)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("Unexpected null value");
        }
    }
}
