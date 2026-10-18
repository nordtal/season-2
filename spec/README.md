# spec

`spec` describes a settings group as an annotated interface (`@ConfigSpec`) and builds the schema
that Steward draws its forms from. `Specs` creates a spec's values and inspects them. `SchemaWriter`
builds the schema tree. `:settings` stores both in the database.

The proxy machinery is [Spec](https://github.com/Revxrsal/spec) (MIT), copied into
`eu.nordtal.season.spec`. The licence is in the repository's [NOTICE](../NOTICE). The header of each
copied file lists what changed.
