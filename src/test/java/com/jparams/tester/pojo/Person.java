package com.jparams.tester.pojo;

public class Person extends Identified
{
    private static String stringValue = null;

    private final String firstName;
    private final String lastName;

    public Person(final Integer id, final String firstName, final String lastName)
    {
        super(id);
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String getFirstName()
    {
        return firstName;
    }

    public String getLastName()
    {
        return lastName;
    }

    @Override
    public String toString()
    {
        if (stringValue != null)
        {
            return stringValue;
        }

        return "Person{"
            + "id=" + getId()
            + ", firstName='" + firstName + '\''
            + ", lastName='" + lastName + '\''
            + '}';
    }

    @Override
    public int hashCode()
    {
        return 123;
    }

    public static void setStringValue(final String value)
    {
        stringValue = value;
    }
}