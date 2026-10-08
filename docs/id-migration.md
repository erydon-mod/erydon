# Permanent ID migrations

`src/main/resources/data/erydon/id_migration.tsv` is the runtime source for block
and item aliases, canonical resource names, and inherited search vocabulary.
Published IDs remain permanent aliases; their internal block models and textures
keep their published paths for resource-pack compatibility.

Positive `source_row` values identify the original reviewed workbook rows.
`source_row=0` identifies a supplemental migration approved directly by the user,
without claiming a workbook row. These approvals are recorded here; a future
workbook export retains these rows from the committed manifest by default;
`--supplemental-manifest` can explicitly select that source. Contradictory
workbook rows are rejected.

On 2026-10-08, the user approved renaming the four upright diagonal framed
glazing blocks from `glazing_framed_{finish}_slope_vertical` to
`glazing_framed_{finish}_vertical_diagonal`, for tinted, silver, crystal, and
bronze. All four published block and item IDs remain aliases, and searches retain
the former Vertical Slope wording alongside Vertical Diagonal. Pitched and
shallow glazing IDs are unchanged.

The complete manifest contains 1,779 migrations: 1,590 permanent block/item alias
pairs and 189 unpublished direct renames. The original 1,775 approvals are
unchanged.
